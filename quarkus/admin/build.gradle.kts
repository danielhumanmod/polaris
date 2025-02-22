/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import io.quarkus.gradle.tasks.QuarkusBuild

plugins {
  alias(libs.plugins.quarkus)
  alias(libs.plugins.jandex)
  alias(libs.plugins.openapi.generator)
  id("polaris-quarkus")
  // id("polaris-license-report")
  id("distribution")
}

dependencies {
  implementation(project(":polaris-core"))
  implementation(project(":polaris-version"))
  implementation(project(":polaris-api-management-service"))
  implementation(project(":polaris-api-iceberg-service"))

  runtimeOnly(project(":polaris-eclipselink"))

  implementation(enforcedPlatform(libs.quarkus.bom))
  implementation("io.quarkus:quarkus-picocli")
  implementation("io.quarkus:quarkus-container-image-docker")

  implementation("org.jboss.slf4j:slf4j-jboss-logmanager")

  testFixturesApi(project(":polaris-core"))

  testFixturesApi(enforcedPlatform(libs.quarkus.bom))
  testFixturesApi("io.quarkus:quarkus-junit5")

  testFixturesApi(platform(libs.testcontainers.bom))
  testFixturesApi("org.testcontainers:testcontainers")
  testFixturesApi("org.testcontainers:postgresql")

  testRuntimeOnly(project(":polaris-eclipselink"))
  testRuntimeOnly("org.postgresql:postgresql")
}

quarkus {
  quarkusBuildProperties.put("quarkus.package.type", "uber-jar")
  // Pull manifest attributes from the "main" `jar` task to get the
  // release-information into the jars generated by Quarkus.
  quarkusBuildProperties.putAll(
    provider {
      tasks
        .named("jar", Jar::class.java)
        .get()
        .manifest
        .attributes
        .map { e -> "quarkus.package.jar.manifest.attributes.\"${e.key}\"" to e.value.toString() }
        .toMap()
    }
  )
}

publishing {
  publications {
    named<MavenPublication>("maven") {
      val quarkusBuild = tasks.getByName<QuarkusBuild>("quarkusBuild")
      artifact(quarkusBuild.runnerJar) {
        classifier = "runner"
        builtBy(quarkusBuild)
      }
    }
  }
}

tasks.named("distZip") { dependsOn("quarkusBuild") }

tasks.named("distTar") { dependsOn("quarkusBuild") }

distributions {
  main {
    contents {
      from("../../NOTICE")
      from("../../LICENSE-BINARY-DIST").rename("LICENSE-BINARY-DIST", "LICENSE")
      from(project.layout.buildDirectory) { include("polaris-quarkus-admin-*-runner.jar") }
    }
  }
}
