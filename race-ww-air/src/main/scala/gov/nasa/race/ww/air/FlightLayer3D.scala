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
package gov.nasa.race.ww.air

import java.util.concurrent.atomic.AtomicInteger

import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.air.InFlightAircraft
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.geo.VarEuclideanDistanceFilter3D
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.ww.{MinClipOrbitView, EyePosListener, RaceView}
import gov.nasa.worldwind.geom.Position

import scala.concurrent.duration._
import scala.collection.immutable.HashSet

/**
  * a FlightLayer that supports 3D models
  * This includes three additional tasks:
  *
  * (1) loading and accessing a collection of 3D models that can be associated with flights.
  * Since 3D models are quite expensive, the association can be optional, i.e. not every flight
  * might have a 3D model, and models can be re-used for different flight entries
  *
  * (2) maintaining a list of InFlightAircraft objects that are close enough to the eye position
  * to warrant a 3D model display. 3D models are scaled with respect to eyepos distance, i.e. they
  * cannot be used in lieu of flightSymbols (which are fixed in size and attitude). This list
  * changes because of view changes *and* flight position changes. The former requires a bulk
  * computation (over all known flights), and therefore has to be aware of animations (i.e. transient
  * eye position changes)
  *
  * (3) proper update of FlightEntry renderables based on visibility of respective InFlightAircraft
  * objects
  *
  * NOTE - if no model is configured, this should not incur any performance penalties.
  * In particular, eye position and proximity updates should only happen if we have configured models
  */
class FlightLayer3D[T <:InFlightAircraft](raceView: RaceView, config: Config) extends FlightLayer[T](raceView,config) with EyePosListener  {

  //--- 3D models and proximities
  val eyeDistanceFilter = new VarEuclideanDistanceFilter3D(Angle0,Angle0,Length0, Meters(config.getDoubleOrElse("model-distance",2000.0)))
  var proximities = HashSet.empty[FlightEntry[T]]

  addLayerModels
  val models = config.getConfigSeq("models").map(FlightModel.loadModel[T])

  if (models.nonEmpty) {
    raceView.addEyePosListener(this)  // will update lastEyePos and proximities
  }

  private val pendingUpdates = new AtomicInteger

  // this can be overridden by layers to add their own generic models
  def addLayerModels: Unit = {
    FlightModel.addDefaultModel("defaultAirplane", FlightModel.defaultSpec)
  }

  def getModel (e: FlightEntry[T]): Option[FlightModel[T]] = {
    models.find( _.matches(e.obj) )
  }

  // we only get these callbacks if there were configured models
  def eyePosChanged(eyePos: Position, animHint: String): Unit = {
    if (models.nonEmpty) {
      pendingUpdates.getAndIncrement
      delay(1.second, () => {
        val ep = raceView.eyePosition
        eyeDistanceFilter.updateReference(Degrees(ep.latitude.degrees), Degrees(ep.longitude.degrees), Meters(ep.getAltitude))
        if (pendingUpdates.decrementAndGet == 0) recalculateProximities
      })
    }
  }

  def recalculateProximities = foreachFlight(updateProximity)

  def updateProximity (e: FlightEntry[T]) = {
    val fpos = e.obj
    val pos = fpos.position
    if (eyeDistanceFilter.pass(pos.φ,pos.λ,fpos.altitude)){
      if (!proximities.contains(e)){
        val m = getModel(e)
        if (m.isDefined) {
          e.setModel(m)
          info(s"set 3D model for $fpos")
          redraw
        } else {
          info (s"no matching/available 3D model for $fpos")
        }
        proximities = proximities + e
      }
    } else {
      if (proximities.contains(e)) {
        if (e.hasModel) {
          e.setModel(None)
          info(s"release model for $fpos")
          redraw
        }
        proximities = proximities - e
      }
    }
  }

  override def addFlightEntryAttributes(e: FlightEntry[T]): Unit = {
    if (models.nonEmpty) updateProximity(e)
    e.addRenderables
  }
  override def updateFlightEntryAttributes (e: FlightEntry[T]): Unit = {
    if (models.nonEmpty) updateProximity(e)
    e.updateRenderables
  }
  override  def releaseFlightEntryAttributes(e: FlightEntry[T]): Unit = {
    e.removeRenderables
  }
}
