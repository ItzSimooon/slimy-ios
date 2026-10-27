plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

// Tests that check art read the real sprite data the app ships.
tasks.test {
    systemProperty("slimy.sprites", rootProject.file("../Shared/Sprites/sprites.json").absolutePath)
}
