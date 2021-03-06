/*
 * Copyright (c) 2016, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
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
package gov.nasa.race.air.translator

import com.typesafe.config.Config
import gov.nasa.race.IdentifiableObject
import gov.nasa.race.air.{FlightCompleted, FlightPos}
import gov.nasa.race.common._
import gov.nasa.race.config._
import gov.nasa.race.geo.LatLonPos
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom._
import org.joda.time.DateTime

import scala.collection.mutable.ArrayBuffer

/**
  * FIXM MessageCollection/NasFlight to FlightPos to translator
  *
  * This translator has to handle both pre-Solace ns5:NasFlight and new (12 sec update)
  * ns5:MessageCollection messages so that we can use it for current data and old archives
  * without reconfiguration
  */
class FIXM2FlightObject (val config: Config=NoConfig)
                 extends FIXMParser[Seq[IdentifiableObject]] with ConfigurableTranslator {
  setBuffered(4096)

  protected var flights = new ArrayBuffer[IdentifiableObject](20)

  override def flatten = true // report as single flight objects

  onStartElement = {
    case "ns5:MessageCollection" => messageCollection // new format (Solace 12sec)
    case "ns5:NasFlight" => nasFlight // old format (one flight per msg)
    case other => stopParsing
  }

  def messageCollection = {
    flights.clear
    whileNextStartElement {
      case "flight" => flight
      case _ => // ignore (<message> does not hold any info)
    }
    if (flights.nonEmpty) setResult(flights)
  }

  def nasFlight = {
    flights.clear
    flight
    if (flights.nonEmpty) setResult(flights)
  }

  def flight = {
    var id, cs: String = null
    var lat, lon, vx, vy: Double = UndefinedDouble
    var alt: Length = UndefinedLength
    var spd: Speed = UndefinedSpeed
    var date, arrivalDate: DateTime = null
    var arrivalPoint: String = null

    whileNextElement { // start elements
      case "flightIdentification" => parseAllAttributes {
        case "computerId" => id = value
        case "aircraftIdentification" => cs = value
        case _ => // ignored
      }

      //--- enRoute info
      case "position" if hasParent("enRoute") =>
        if (parseAttribute("positionTime")) date = DateTime.parse(value)
      case "pos" if hasParent("location") =>
        lat = readDouble
        lon = readNextDouble
      case "x" => vx = readDouble // we just need it for heading computation and assume same 'uom'
      case "y" => vy = readDouble
      case "surveillance" if hasParent("actualSpeed") => spd = readSpeed
      case "altitude" => alt = readAltitude

      //--- completed flights
      case "arrival" => if (parseAttribute("arrivalPoint")) arrivalPoint = value
      case "actual" if hasSomeParent("arrival") => arrivalDate = DateTime.parse(readAttribute("time"))

      case _ =>  // ignore

    } { // end elements
      case "flight" | "ns5:NasFlight" =>
        if (cs != null) {
          if (arrivalDate != null) {
            flights += FlightCompleted(id, cs, arrivalPoint, arrivalDate)
          } else {
            if (lat.isDefined && lon.isDefined && date != null &&
              vx.isDefined && vy.isDefined && spd.isDefined && alt.isDefined) {
              flights += new FlightPos(id, cs, LatLonPos(Degrees(lat), Degrees(lon)),
                                       alt, spd, Degrees(Math.atan2(vx, vy).toDegrees), date)
            } else {
              //println(s"@@@ rejected flight: $cs $lat $lon $date $vx $vy $spd $alt")
            }
          }
        }
      case _ =>  // ignore
    }
  }
}