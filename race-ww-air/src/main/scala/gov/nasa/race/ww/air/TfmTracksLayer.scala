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

import java.awt.Color

import akka.actor.Actor.Receive
import com.typesafe.config.Config
import gov.nasa.race.air.{AirLocator, TFMTrack, TFMTracks}
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.ww.{Images, RaceView}
import gov.nasa.race.ww.track.TrackLayer

/**
  * a RaceViewerActor WWJ layer to display TFM track data
  */
class TfmTracksLayer (raceView: RaceView,config: Config)
           extends TrackLayer[TFMTrack](raceView,config) with AirLocator {

  override def defaultSymbolColor = Color.magenta
  override def defaultSymbolImage = Images.getPlaneImage(color)

  def handleTfmTracksLayerMessage: Receive = {
    case BusEvent(_,msg: TFMTracks,_) =>
      incUpdateCount
      msg.tracks foreach { tfmTrack =>
        getTrackEntry(tfmTrack) match {
          case Some(trackEntry) =>
            if (tfmTrack.nextPos.isEmpty) removeTrackEntry(trackEntry)  // completed
            else updateTrackEntry(trackEntry,tfmTrack)

          case None =>
            if (!tfmTrack.nextPos.isEmpty) addTrackEntry(tfmTrack) // only add if this wasn't the completed message
        }
      }
  }

  override def handleMessage = handleTfmTracksLayerMessage orElse super.handleMessage
}