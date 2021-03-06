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

package gov.nasa.race.actor

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.{PublishingRaceActor, RaceContext, SubscribingRaceActor}
import gov.nasa.race.geo.{GeoUtils, LatLonPos}
import gov.nasa.race.track._
import gov.nasa.race.uom.Length
import gov.nasa.race.uom.Length._

import scala.collection.mutable.{HashMap => MHashMap, Set => MSet}

/**
  * base type for actors that detect and report proximity tracks
  */
trait ProximityActor extends SubscribingRaceActor with PublishingRaceActor {

  abstract class RefEntry {
    val proximities: MHashMap[String,TrackedObject] = MHashMap.empty

    def updateRef (t: TrackedObject): Unit = {}
    def checkProximity (t: TrackedObject): Unit
  }

  // the distance we consider as proximity
  def defaultDistance: Length = NauticalMiles(5)
  val distanceInMeters = NauticalMiles(config.getDoubleOrElse("distance-nm", defaultDistance.toNauticalMiles)).toMeters

  val refs: MHashMap[String,RefEntry] = MHashMap.empty


  def updateProximities(track: TrackedObject): Unit = refs.foreach( _._2.checkProximity(track))
}

/**
  * a ProximityActor with configured, static references
  */
class StaticProximityActor (val config: Config) extends ProximityActor {
  import ProximityEvent._

  class StaticRefEntry (val id: String, pos: LatLonPos, altitude: Length) extends RefEntry {
    override def checkProximity (track: TrackedObject) = {
      val tId = track.cs
      val tLat = track.position.φ.toRadians
      val tLon = track.position.λ.toRadians
      val dist = GeoUtils.euclideanDistanceRad(pos.φ.toRadians,pos.λ.toRadians,tLat,tLon,altitude.toMeters)

      if (dist <= distanceInMeters) {
        val flags = if (proximities.contains(tId)) ProxChange else ProxNew
        proximities += (tId -> track)
        publish(ProximityEvent(new ProximityReference(id,track.date,pos,altitude), Meters(dist), flags, track))
      } else {
        if (proximities.contains(tId)) {
          proximities -= tId
          publish(ProximityEvent(new ProximityReference(id,track.date,pos,altitude), Meters(dist), ProxDrop, track))
        }
      }
    }
  }

  // override in subclasses that have well known locations and hence don't need lat/lon/alt (e.g. Airports in .air)
  protected def initRefs: Unit = {
    config.getConfigArray("refs") foreach { conf =>
      val id = conf.getString("id") // mandatory
      for (lat <- conf.getOptionalDouble("lat");
           lon <- conf.getOptionalDouble("lon");
           alt <- conf.getOptionalDouble("altitude-ft")) {
        refs += (id -> new StaticRefEntry(id, LatLonPos.fromDegrees(lat,lon), Feet(alt)))
      }
    }
  }

  override def handleMessage = {
    case BusEvent(_,track:TrackedObject,_) => updateProximities(track)
  }
}

/**
  * actor that reports proximity track updates
  *
  * this actor subscribes to two sets of input channels - an optional one for reference object updates,
  * and the regular 'read-from' on which potential proximitiy tracks are reported.
  * It acts as sort of a decorating filter
  *
  * the actor can manage a number of reference tracks and publishes ProximityUpdate messages each time a track update arrives
  * that is within a configured distance of one of the managed references
  */
class DynamicProximityActor (val config: Config) extends ProximityActor {
  import ProximityEvent._

  class DynamicRefEntry (val refEstimator: TrackedObjectEstimator) extends RefEntry {
    override def updateRef (track: TrackedObject) = refEstimator.addObservation(track)

    protected def getDistanceInMeters (track: TrackedObject): Double = {
      val re = refEstimator

      val tLat = track.position.φ.toRadians
      val tLon = track.position.λ.toRadians

      GeoUtils.euclideanDistanceRad(re.lat.toRadians, re.lon.toRadians, tLat, tLon, re.altitude.toMeters)
    }

    override def checkProximity (track: TrackedObject) = {
      val re = refEstimator
      val tId = track.cs

      if (tId != re.track.cs) {  // don't try to be a proximity to yourself
        if (re.estimateState(track.date.getMillis)) {
          val dist = getDistanceInMeters(track)

          if (dist <= distanceInMeters) {
            val flags = if (proximities.contains(tId)) ProxChange else ProxNew
            proximities += (tId -> track)
            publish(ProximityEvent(new ProximityReference(re, track.date), Meters(dist), flags, track))
          } else {
            if (proximities.contains(tId)) {
              proximities -= tId
              publish(ProximityEvent(new ProximityReference(re, track.date), Meters(dist), ProxDrop, track))
            }
          }
        }
      }
    }
  }

  // our second input channel set (where we get the reference objects from)
  val readRefFrom = MSet.empty[String]

  // used to (optionally) estimate reference positions when receiving proximity updates
  val refEstimatorPrototype: TrackedObjectEstimator = getConfigurableOrElse("estimator")(new HoldEstimator)


  override def onInitializeRaceActor(raceContext: RaceContext, actorConf: Config): Boolean = {
    // we could check for readFrom channel identity, but the underlying subscription storage uses sets anyways
    readRefFrom ++= actorConf.getOptionalStringList("read-ref-from")
    readRefFrom.foreach { channel => busFor(channel).subscribe(self,channel) }
    super.onInitializeRaceActor(raceContext, actorConf)
  }

  override def handleMessage = {
    case BusEvent(chan:String,track:TrackedObject,_) =>
      // note that both refs and proximities might be on the same channel
      if (readRefFrom.contains(chan)) updateRef(track)
      if (readFrom.contains(chan)) updateProximities(track)

    case BusEvent(_,msg:Any,_) =>  // all other BusEvents are ignored
  }

  def updateRef(track: TrackedObject): Unit = {
    val e = refs.getOrElseUpdate(track.id, new DynamicRefEntry(refEstimatorPrototype.clone))
    e.updateRef(track)
  }
}

/**
  * this is essentially a DynamicProximityActor that does not have to know if something is a new or dropped
  * proximity, hence it does not have to maintain a proximities collection
  *
  * note this implementation allows for multiple collisions to occur, even with the same track as long as there
  * is at least one separation event between the collisions. Only the entry of a track into the collision radius
  * is reported, i.e. we should not get consecutive collision events while the track is within this radius
  */
class CollisionDetector (config: Config) extends DynamicProximityActor(config) {
  import ProximityEvent._

  override def defaultDistance = Feet(500) // NMAC distance

  class CollisionRefEntry(refEstimator: TrackedObjectEstimator) extends DynamicRefEntry(refEstimator) {
    var collisions = Set.empty[String] // keep track of reported/ongoing collisions

    override def checkProximity (track: TrackedObject) = {
      val re = refEstimator
      val tcs = track.cs

      if (tcs != re.track.cs) { // don't try to be a proximity to yourself
        if (re.estimateState(track.date.getMillis)) {
          val dist = getDistanceInMeters(track)
          if (dist <= distanceInMeters) {
            if (!collisions.contains(tcs)) {
              collisions = collisions + tcs
              publish(ProximityEvent(new ProximityReference(re, track.date), Meters(dist), ProxCollision, track))
            }
          } else {
            if (collisions.nonEmpty) collisions = collisions - tcs
          }
        }
      }
    }
  }

  override def updateRef(track: TrackedObject): Unit = {
    val e = refs.getOrElseUpdate(track.id, new CollisionRefEntry(refEstimatorPrototype.clone))
    e.updateRef(track)
  }
}