// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import software.aws.toolkits.gradle.intellij.IdeFlavor
import software.aws.toolkits.gradle.intellij.IdeVersions

plugins {
    id("org.jetbrains.intellij")
}

val ideProfile = IdeVersions.ideProfile(project)

val toolkitVersion: String by project
val publishToken: String by project
val publishChannel: String by project

version = "$toolkitVersion-${ideProfile.shortName}"

intellij {
    version.set(ideProfile.community.version())
    localPath.set(ideProfile.community.localPath())
    plugins.set(
        listOf(
            project(":plugin-core")
        )
    )

    updateSinceUntilBuild.set(false)
    instrumentCode.set(false)
}

dependencies {
    implementation(project(":plugin-amazonq:chat"))
    implementation(project(":plugin-amazonq:codetransform"))
    implementation(project(":plugin-amazonq:codewhisperer"))
    implementation(project(":plugin-amazonq:mynah-ui"))
    implementation(project(":plugin-amazonq:shared"))
}

configurations {
    // Make sure we exclude stuff we either A) ships with IDE, B) we don't use to cut down on size
    runtimeClasspath {
        exclude(group = "org.slf4j")
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }
}

val moduleOnlyJar = tasks.create<Jar>("moduleOnlyJar") {
    archiveClassifier.set("module-only")
    // empty jar
}

val moduleOnlyJars by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    // If you want this configuration to share the same dependencies, otherwise omit this line
    extendsFrom(configurations["implementation"], configurations["runtimeOnly"])
}

artifacts {
    add("moduleOnlyJars", moduleOnlyJar)
}
