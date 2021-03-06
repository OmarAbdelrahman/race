/*
 * Copyright (c) 2016, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package gov.nasa.race.ww

import java.awt.Font
import java.util.{Timer, TimerTask}
import java.util.concurrent.Semaphore

import akka.actor.{ActorRef, Props}
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{ContinuousTimeRaceActor, RaceContext, _}
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing.{Redrawable, _}
import gov.nasa.race.util.FileUtils
import gov.nasa.worldwind.geom.Position
import gov.nasa.worldwind.layers.Layer

import scala.collection.JavaConverters._
import scala.collection.immutable.ListMap
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.swing._


/**
  * the master actor for geospatial display of RACE data channels,
  * using NASA WorldWind for the heavy lifting
  *
  * Each layer is instantiated from the config, each SuscribingRaceLayer
  * has an associated actor which is created/supervised by this RaceViewer
  * instance
  */
class RaceViewerActor(val config: Config) extends ContinuousTimeRaceActor
               with SubscribingRaceActor with PublishingRaceActor with ParentRaceActor {

  var view: Option[RaceView] = None // we can't create this from a Akka thread since it does UI transcations

  var initTimedOut = false // WorldWind initialization might do network IO - we have to check for timeouts

  //--- RaceActor callbacks

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config): Boolean = {
    scheduleOnce(initTimeout - 100.millisecond){
      if (!view.isDefined){
        warning("timeout during view initialization")
        initTimedOut = true
      }
    }

    invokeAndWait { // this is executed in the AWT event dispatch thread
      try {
        view = Some(new RaceView(RaceViewerActor.this))
      } catch {
        case x: Throwable => x.printStackTrace
      }
    }
    view.isDefined && !initTimedOut && super.onInitializeRaceActor(rc, actorConf)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    ifSome(view) { v =>
      if (v.displayable) {
        info(s"${name} closing WorldWind window..")
        v.close
        info(s"${name} WorldWind window closed")
      } else {
        info(s"${name} WorldWind window already closed")
      }
    }

    super.onTerminateRaceActor(originator)
  }
}


object RaceView {

  //--- eye position animation hints
  final val CenterClick = "CenterClick"
  final val CenterDrag = "CenterDrag"
  final val Zoom = "Zoom"
  final val Pan = "Pan"
  final val Goto = "Goto"  // the catch all

  final val SelectedLayer = "selected layer"
  final val SelectedObject = "selected object"
}
import gov.nasa.race.ww.RaceView._

/**
  * this is a viewer state facade we pass down into our components, which
  * are executing in the UI thread(s) and hence should not be able
  * to directly access our actor internals.
  *
  * This follows the same approach as RaceLayer/RaceLayerActor to map thread
  * boundaries to types. It also acts as a mediator/broker
  *
  * NOTE this class has to be thread-aware, don't introduce race conditions
  * by exposing objects.
  *
  * Both ctor and methods are supposed to be executed from the UI thread
  */
class RaceView (viewerActor: RaceViewerActor) extends DeferredEyePositionListener {
  implicit val log = viewerActor.log
  def initTimedOut = viewerActor.initTimedOut

  setWorldWindConfiguration // NOTE - this has to happen before we load any WorldWind classes

  val gotoTime = config.getIntOrElse("goto-time", 4000)
  val defaultLabelFont = config.getFontOrElse("label-font",  new Font(null,Font.PLAIN,13))
  val defaultSubLabelFont = config.getOptionalFont("sublabel-font") // none if not explicitly set

  // we want to avoid several DeferredXListeners because of the context switch overhead
  // hence we have a secondary listener level here
  var eyePosListeners = List.empty[EyePosListener]
  def addEyePosListener (newListener: EyePosListener) = eyePosListeners = newListener :: eyePosListeners
  def removeEyePosListener(listener: EyePosListener) = eyePosListeners = eyePosListeners.filter(_.ne(listener))

  var layerListeners = List.empty[LayerListener]
  def addLayerListener (newListener: LayerListener) = layerListeners = newListener :: layerListeners
  def removeLayerListener (listener: LayerListener) = layerListeners = layerListeners.filter(_.ne(listener))

  var objectListener = List.empty[ObjectListener]
  def addObjectListener (newListener: ObjectListener) = objectListener = newListener :: objectListener
  def removeObjectListener (listener: ObjectListener) = objectListener = objectListener.filter(_.ne(listener))

  val layers = createLayers
  val frame = new WorldWindFrame(viewerActor.config, this) // this creates the wwd instance

  val redrawManager = RedrawManager(wwd.asInstanceOf[Redrawable]) // the shared one, layers/panels can have their own
  val inputHandler = wwdView.getViewInputHandler.asInstanceOf[RaceViewInputHandler]
  var layerController: Option[LayerController] = None

  val emptyLayerInfoPanel = new EmptyPanel(this)
  val emptyObjectPanel = new EmptyPanel(this)

  val panels: ListMap[String,PanelEntry] = createPanels
  panels.foreach{ e => frame.initializePanel(e._2) }

  // this has to be deferred because WWJs setViewInputHandler() does not initialize properly
  ifInstanceOf[RaceViewInputHandler](wwd.getView.getViewInputHandler) {_.attachToRaceView(this)}

  if (!initTimedOut){
    frame.open
  } else {
    frame.dispose
  }

  //---- end initialization, from here on we need to be thread safe

  def config = viewerActor.config
  def displayable = frame.displayable
  def simClock = viewerActor.simClock
  def updatedSimTime = viewerActor.updatedSimTime
  def close = frame.close

  // WWD accessors
  def wwd = frame.wwd
  def wwdView = frame.wwd.getView
  def eyePosition = frame.wwd.getView.getEyePosition

  for (
    cachePath <- config.getOptionalString("cache-dir");
    dir <- FileUtils.ensureDir(cachePath)
  ) ConfigurableWriteCache.setRoot(dir)

  def setWorldWindConfiguration = {
    // we use our own app config document which takes precedence over Worldwind's config/worldwind.xml
    // note that we also provide a separate config/worldwind.layers.xml (which is referenced from worldwind.xml)
    System.setProperty("gov.nasa.worldwind.app.config.document", "config/race-worldwind.xml")
  }

  def createLayers = {
    config.getOptionalConfigList("layers").foldLeft(Seq.empty[RaceLayer]){ (seq,layerConfig) =>
      val layerName = layerConfig.getString("name")
      val layerClsName = layerConfig.getString("class")
      info(s"creating layer '$layerName': $layerClsName")
      val layer = newInstance[RaceLayer](layerClsName, Array(classOf[RaceView], classOf[Config]), Array(this, layerConfig))
      if (layer.isDefined){
        seq :+ layer.get
      } else {
        error(s"layer $layerName did not instantiate")
        seq
      }
    }
  }

  def getLayer (name: String) = layers.find(l => l.name == name)

  /**
    * this method does what Akka tries to avoid - making the actor object
    * available in the caller context. Use with extreme care, and only use the
    * returned actor reference for thread safe operations
    * The reason why we provide this function is that we have a number of
    * constructs (such as layers or panels) that consist of a pair of an actor
    * and a Swing/WorldWind object. Such pairs are subject to context switches,
    * i.e. are inherently dangerous with respect to threading. While we do provide
    * helper constructs such as AkkaSwingBridge, there is no general Swing-to-Akka
    * interface other than sending messages. If we need to query actor field values
    * from Swing this would require ask patterns, which carries a significant
    * performance penalty
    */
  def createActor[T<:RaceActor :ClassTag](name: String)(f: => T): T = {
    val semaphore = new Semaphore(0)
    var actor: T = null.asInstanceOf[T]
    def instantiateActor: T = {
      actor = f            // (1)
      semaphore.release()
      actor
    }
    val actorRef = viewerActor.context.actorOf(Props(instantiateActor),name)
    semaphore.acquire()    // block until (1) got executed
    viewerActor.addChild(RaceActorRec(actorRef,actor.config))
    actor
  }

  def createPanels: ListMap[String,PanelEntry] = {
    if (config.hasPath("panels")) createPanels( config.getOptionalConfigList("panels"))
    else createDefaultPanels
  }

  def createPanels (panelConfigs: Seq[Config]): ListMap[String,PanelEntry] = {
    panelConfigs.foldLeft(ListMap.empty[String,PanelEntry]) { (map,panelConfig) =>
      try {
        val name = panelConfig.getString("name")
        val tooltip = panelConfig.getStringOrElse("tooltip", "click to hide/show panel")
        val expand = panelConfig.getBooleanOrElse("expand", true)

        info(s"creating console panel $name")
        val panel = newInstance[Component](panelConfig.getString("class"),
          Array(classOf[RaceView], classOf[Option[Config]]),
          Array(this, Some(panelConfig))).get
        panel.styled('consolePanel)
        map + (name -> PanelEntry(name, panel, tooltip, expand))
      } catch {
        case t: Throwable => error(s"exception creating panel: $t"); map
      }
    }
  }

  def createDefaultPanels: ListMap[String,PanelEntry] = {
    val collapsed = config.getOptionalStringList("collapse-panels").toSet
    def panelEntry(name: String, c: Component, tt: String) = name -> PanelEntry(name,c,tt,!collapsed.contains(name))
    def styled (c: Component) = c.styled('consolePanel)

    ListMap(
      panelEntry("clock", styled(new BasicClockPanel(this)), "click to hide/show clocks"),
      panelEntry("view", styled(new ViewPanel(this)), "click to hide/show viewing parameters"),
      panelEntry("sync", styled(new SyncPanel(this)), "click to hide/show view synchronization"),
      panelEntry("layers", styled(new LayerListPanel(this)), "click to hide/show layer list"),
      panelEntry(SelectedLayer, styled(emptyLayerInfoPanel), "click to hide/show selected layer"),
      panelEntry(SelectedObject, styled(emptyObjectPanel), "click to hide/show selected object")
    )
  }

  def showConsolePanels(setVisible: Boolean) = frame.showConsolePanels(setVisible)
  def showConsolePanel(name: String, setVisible: Boolean) = frame.showConsolePanel(name, setVisible)

  // layer/object panel selection
  def setLayerPanel (c: Component) = if (panels.contains(SelectedLayer)) frame.setPanel(SelectedLayer, c)
  def dismissLayerPanel = if (panels.contains(SelectedLayer)) frame.setPanel(SelectedLayer, emptyLayerInfoPanel)

  def setObjectPanel (c: Component) = if (panels.contains(SelectedObject)) frame.setPanel(SelectedObject, c)
  def dismissObjectPanel = if (panels.contains(SelectedObject)) frame.setPanel(SelectedObject, emptyObjectPanel)


  // we need this here because of universe specific loaders
  def newInstance[T: ClassTag] (clsName: String,
                                argTypes: Array[Class[_]]=null, args: Array[Object]=null): Option[T] = {
    viewerActor.newInstance(clsName,argTypes,args)
  }

  // called by RaceViewInputHandler
  def newTargetEyePosition (eyePos: Position, animationHint: String) = eyePosListeners.foreach(_.eyePosChanged(eyePos,animationHint))

  //--- track local (panel) user actions, used to avoid sync resonance
  var lastUserAction: Long = 0
  def trackUserAction(f: =>Unit) = {
    lastUserAction = System.currentTimeMillis() // make sure we update before we call the action
    f
  }
  def millisSinceLastUserAction = {
    Math.min(System.currentTimeMillis - lastUserAction, inputHandler.millisSinceLastUserInput)
  }

  //--- view (eye position) transitions

  // this one does not animate. Use for objects that have to stay at the same screen coordinates,
  // but the map is going to be updated discontinuously
  def centerOn (pos: Position) = {
    inputHandler.stopAnimators
    //inputHandler.addCenterAnimator(eyePosition, pos, true) // ?bug - this just causes weird zoom-out animation
    wwdView.setEyePosition(new Position(pos,eyePosition.getElevation))
  }
  // this one does a smooth transition to a new center, i.e. the map will update smoothly, but
  // objects at the center positions will jump
  def panToCenter (pos: Position, transitionTime: Long=500) = {
    inputHandler.stopAnimators
    inputHandler.addEyePositionAnimator(transitionTime,eyePosition,new Position(pos,eyePosition.getElevation))
  }
  def zoomTo (zoom: Double) = {
    inputHandler.stopAnimators
    inputHandler.addZoomAnimator(eyePosition.getAltitude, zoom)
  }
  def panTo (pos: Position, eyeAltitude: Double) = {
    inputHandler.stopAnimators
    inputHandler.addPanToAnimator(pos,ZeroWWAngle,ZeroWWAngle,eyeAltitude,true) // always center on surface
  }
  def setEyePosition (pos: Position, animTime: Long) = {
    inputHandler.stopAnimators
    inputHandler.addEyePositionAnimator(animTime,eyePosition,pos)
  }

  //--- layer change management
  def setLayerController (controller: LayerController) = layerController = Some(controller)
  def layerChanged (layer: Layer) = layerListeners.foreach(_.layerChanged(layer))
  def changeLayer (name: String, enable: Boolean) = layerController.foreach(_.changeLayer(name,enable))

  //--- object change management
  def objectChanged (obj: LayerObject, action: String) = objectListener.foreach(_.objectChanged(obj,action))
  def changeObject (id: String, layerName: String, action: String) = {
    ifSome(getLayer(layerName)){ _.changeObject(id,action)}
  }

  def configuredLayerCategories(default: Set[String]): Set[String] = {
    if (config.hasPath("layer-categories")) config.getStringList("layer-categories").asScala.toSet else default
  }

  def redraw = redrawManager.redraw()
  def redrawNow = redrawManager.redrawNow()

  def getInViewChecker = InViewChecker(wwd)
}