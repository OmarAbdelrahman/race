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

package gov.nasa.race.air.translator

import java.lang.Double.isFinite

import com.typesafe.config.Config
import gov.nasa.race.air.{AsdexTrack, AsdexTrackType, AsdexTracks, VerticalDirection}
import gov.nasa.race.common.XmlParser
import gov.nasa.race.config._
import gov.nasa.race.geo.LatLonPos
import gov.nasa.race.ifNotNull
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import org.joda.time.DateTime

import scala.Double.NaN
import scala.collection.mutable.ArrayBuffer

/**
  * translator for SWIM ASDE-X asdexMsg messages to raw AsdexTracks (with incomplete delta info)
  */
class AsdexMsg2AsdexTracks(val config: Config=NoConfig) extends XmlParser[AsdexTracks]
                                                        with ConfigurableTranslator {
  setBuffered(8192)

  //-- the XML messages we handle

  onStartElement = {
    case "asdexMsg" => asdexMsg
    case other => stopParsing
  }

  def asdexMsg = {
    var airport: String = null
    val tracks = new ArrayBuffer[AsdexTrack]

    whileNextElement {
      case "airport" => airport = setAirport(readText)
      case "positionReport" => positionReport(tracks)
    } {
      case "asdexMsg" => setResult(new AsdexTracks(airport,tracks))
      case _ => // ignore
    }
  }

  def positionReport (tracks: ArrayBuffer[AsdexTrack]): Unit = {
    // note that we have to use different values for optionals that might not be in a delta update so that
    // we can distinguish from cached values
    var display = true
    var trackId: String = null
    var date: DateTime = null
    var latDeg, lonDeg: Double = NaN
    var altFt: Double = NaN
    var hdgDeg: Double = NaN
    var spdMph: Double = NaN
    var drop: Boolean = false
    var tgtType: String = null
    var ud: String = null
    var acId: String = null
    var acType: String = null
    var gbs: Boolean = false

    val fullReport = parseAttribute("full") && value == "true"

    whileNextElement {
      //--- start elements
      case "track" => trackId = readText()
      case "time" => date = DateTime.parse(readText())
      case "latitude" => latDeg = readDouble
      case "longitude" => lonDeg = readDouble
      // apparently some messages have malformed elements such as <aircraftId r="1"/>

      case "tse" => drop = readInt() == 1  // track service ends
      case "di" => display = readInt() != 0  // display
      case "ud" => ud = readText   // up/down
      case "gbs" => gbs = readInt() == 1 // ground bit (default false)

      case "aircraftId" => acId = readText
      case "tgtType" => tgtType = readText
      case "acType" => acType = readText
      case "altitude" => altFt = readDouble
      case "heading" => hdgDeg = readDouble
      case "speed" => spdMph = readDouble
      case _ => // ignored
    } {
      //--- end elements
      case "positionReport" =>
        // our minimal requirements are a dated lat/lon position and a trackId
        if (trackId != null && (date != null)) {
          ifNotNull(createTrack(trackId, date, display, latDeg, lonDeg, altFt, hdgDeg, spdMph,
            drop, tgtType, ud, acId, acType, gbs))( tracks += _)
        }
        return // done
      case _ => // ignore
    }
  }

  //-- override these if we report full tracks (as opposed to deltas)
  protected def setAirport (ap: String) = ap

  protected def createTrack (trackId: String, date: DateTime, display: Boolean,
                   latDeg: Double, lonDeg: Double,
                   altFt: Double, hdgDeg: Double, spdMph: Double,
                   drop: Boolean, tgtType: String, ud: String,
                   acId: String, acType: String, gbs: Boolean): AsdexTrack = {

    // if input values are defined, use those. Otherwise use the last value or the fallback if there was none
    val lat = if (isFinite(latDeg)) Degrees(latDeg) else UndefinedAngle
    val lon = if (isFinite(lonDeg)) Degrees(lonDeg) else UndefinedAngle

    if (lat.isDefined && lon.isDefined) {
      val alt = if (isFinite(altFt)) Feet(altFt) else UndefinedLength
      val hdg = if (isFinite(hdgDeg)) Degrees(hdgDeg) else UndefinedAngle
      val spd = if (isFinite(spdMph)) UsMilesPerHour(spdMph) else UndefinedSpeed
      val cs = if (acId != null) getCallsign(acId,trackId) else trackId
      val act = if (acType != null) Some(acType) else None
      val tt = if (tgtType != null) getTrackType(tgtType) else AsdexTrackType.Unknown
      val vert = if (ud != null) getVerticalDirection(ud) else VerticalDirection.Unknown
      new AsdexTrack(trackId, cs, date, LatLonPos(lat, lon), spd, hdg, alt, tt, display, drop, vert, gbs, act)

    } else null
  }

  //--- specific text transformers

  def getTrackType (tt: String) = {
    tt match {
      case "aircraft" => AsdexTrackType.Aircraft
      case "vehicle" => AsdexTrackType.Vehicle
      case _ => AsdexTrackType.Unknown
    }
  }

  def getVerticalDirection (ud: String) = {
    ud match {
      case "up" => VerticalDirection.Up
      case "down" => VerticalDirection.Down
      case _ => VerticalDirection.Unknown
    }
  }

  def getCallsign (s: String, trackId: String) = if (s == "UNKN") trackId else s
}
