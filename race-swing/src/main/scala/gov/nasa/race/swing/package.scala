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

package gov.nasa.race

import java.awt.{Component => AWTComponent, Window => AWTWindow, _}
import javax.swing.{SwingConstants, SwingUtilities}

import scala.language.{implicitConversions, reflectiveCalls}
import scala.swing.{Component, ListView}

/**
 * common swing utilities
 *
 * (<2do> - some of this will go into scala-swing)
 */
package object swing {

  implicit def pairToPoint (pair: (Int,Int)): Point = new Point(pair._1, pair._2)
  implicit def pairToDimension (pair: (Int,Int)): Dimension = new Dimension(pair._1, pair._2)
  implicit def quadToInsets (quad: (Int,Int,Int,Int)): Insets = new Insets(quad._1, quad._2, quad._3, quad._4)
  implicit def quadToRectangle (quad: (Int,Int,Int,Int)): Rectangle = new Rectangle(quad._1, quad._2, quad._3, quad._4)

  def invokeLater(f: => Any) = {
    SwingUtilities.invokeLater( new Runnable {
      override def run() = f
    })
  }

  def invokeAndWait(f: => Any) = {
    SwingUtilities.invokeAndWait( new Runnable {
      override def run() = f
    })
  }

  def executeInEDT (f: => Any) = {
    if (EventQueue.isDispatchThread) f
    else invokeLater { f }
  }

  //--- missing callbacks and accessors (<2do> - integrate into javax.swing)
  implicit class RichComponent (val cThis: AWTComponent) {
    def topLevel: AWTWindow = {
      var c: AWTComponent = cThis
      do {
        val parent = c.getParent
        if (parent == null) {
          return c.asInstanceOf[AWTWindow]
        }
        c = parent
      } while (c != null)
      null
    }
  }

  def getDefaultConfiguration = GraphicsEnvironment.getLocalGraphicsEnvironment.getDefaultScreenDevice.getDefaultConfiguration
  def asGraphics2D(g:Graphics) = g.asInstanceOf[Graphics2D]

  object Direction extends Enumeration {
    type Direction = Value
    val North = Value(SwingConstants.NORTH)
    val East  = Value(SwingConstants.EAST)
    val South = Value(SwingConstants.SOUTH)
    val West  = Value(SwingConstants.WEST)
  }

  final val NoInsets = new Insets(0,0,0,0)

  class Filler extends Component

  class Separator extends Component

  //--- generalized drawables
  trait Redrawable {
    def redraw(): Unit
    def redrawNow(): Unit
  }

  abstract class ItemRenderPanel[-A] extends GBPanel {
    def configure (list: ListView[_], isSelected: Boolean, focused: Boolean, item: A, index: Int)
  }
  class ListItemRenderer[-A,B <: ItemRenderPanel[A]](renderer: B) extends ListView.AbstractRenderer[A,B](renderer) {
    def configure(list: ListView[_], isSelected: Boolean, focused: Boolean, item: A, index: Int) = {
      renderer.configure(list,isSelected,focused,item,index)
    }
  }
}
