import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

// Segredos locais (local.properties não vai para o git)
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.example.metrotime"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.metrotime"
        minSdk = 31
        targetSdk = 34
        versionCode = 45
        versionName = "3.9.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        // Só português e inglês: as traduções das bibliotecas para as outras ~80 línguas ocupavam ~1 MB
        resourceConfigurations += listOf("pt", "en")
        buildConfigField("String", "METRO_API_TOKEN", "\"${localProps.getProperty("metro.api.token", "")}\"")
    }

    buildTypes {
        release {
            // A mesma assinatura das versões de teste já instaladas (e das que a app descarrega para se atualizar):
            // a versão otimizada instala-se por cima, sem perder dados
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Split APK by CPU architecture — MapLibre ships ~18MB of native libs PER ABI
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Bibliotecas nativas comprimidas no APK (as do MapLibre são ~11 MB): APK mais pequeno para enviar e
        // descarregar; o Android descomprime-as ao instalar
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // Removed material-icons-extended (~30MB) — using inline vectors instead
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation("androidx.gridlayout:gridlayout:1.0.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // MapLibre Android SDK
    implementation("org.maplibre.gl:android-sdk:11.5.2")

    // Lifecycle ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")

    // Google GTFS Realtime Bindings
    implementation("org.mobilitydata:gtfs-realtime-bindings:0.0.8")

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.database)
    implementation(libs.firebase.storage) // Added for profile pictures
    
    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Credential Manager for Google Sign-In
    implementation("androidx.credentials:credentials:1.2.2")
    implementation("androidx.credentials:credentials-play-services-auth:1.2.2")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Testes da lógica pura (data/cp)
    testImplementation(libs.junit)
    // org.json a sério nos testes (o do Android não funciona fora do telemóvel): para ler a rede da CP e as amostras
    testImplementation("org.json:json:20240303")
}
