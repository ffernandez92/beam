/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * License); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
  groovy
  id("org.apache.beam.module")
}

repositories {
  mavenCentral()
}

val library = project.extensions.extraProperties["library"] as Map<String, Map<String, String>>

dependencies {
  implementation(library.getValue("groovy").getValue("groovy_all"))
  implementation("commons-cli:commons-cli:1.9.0")
  permitUnusedDeclared("commons-cli:commons-cli:1.9.0") // BEAM-11761
}

task("runJavaExamplesValidationTask") {
  group = "Verification"
  description = "Run the Beam quickstart across all Java runners"
  dependsOn(":runners:direct-java:runQuickstartJavaDirect")
  dependsOn(":runners:google-cloud-dataflow-java:runQuickstartJavaDataflow")
  dependsOn(":runners:spark:3:runQuickstartJavaSpark")
  dependsOn(":runners:flink:1.19:runQuickstartJavaFlinkLocal")
  dependsOn(":runners:direct-java:runMobileGamingJavaDirect")
  dependsOn(":runners:google-cloud-dataflow-java:runMobileGamingJavaDataflow")
  dependsOn(":runners:twister2:runQuickstartJavaTwister2")
  dependsOn(":runners:google-cloud-dataflow-java:runMobileGamingJavaDataflowBom")
}
