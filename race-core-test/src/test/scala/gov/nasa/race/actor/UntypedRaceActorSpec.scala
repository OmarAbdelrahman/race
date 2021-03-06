/*
 * Copyright (c) 2017, United States Government, as represented by the
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

import akka.event.Logging
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.test.RaceActorSpec
import org.joda.time.DateTime
import org.scalatest.FlatSpecLike
import test.TestJavaRaceActor
import scala.concurrent.duration._

/**
  * test for RaceActors that are implemented in Java
  */
class UntypedRaceActorSpec extends RaceActorSpec with FlatSpecLike {

  "a test.TestJavaRaceActor" should "respond with PONG when receiving PING messages" in {
    runRaceActorSystem(Logging.InfoLevel) {

      val conf = s"""
              name = "javaActor"
              read-from = "/input"
              write-to = "/output"
          """
      val actor = addTestActor(classOf[TestJavaRaceActor], "javaActor", createConfig(conf))
      var gotResponse = false;

      printTestActors
      initializeTestActors
      startTestActors(DateTime.now)

      expectBusMsg("/output", 2.seconds, publish("/input", "PING")) {
        case BusEvent(_, msg: String, _) =>
          println(s"got a String on /input : $msg")
          if (msg == "PONG") gotResponse = true
        case BusEvent(_,msg,_) => fail(s"unexpected msg on /input: $msg")
      }

      gotResponse.shouldEqual(true)

      terminateTestActors
    }
  }
}
