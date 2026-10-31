import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signature de release : ~/.sonne/keystore.properties (jamais dans le dépôt).
// Clés attendues : storeFile, storePassword, keyAlias, keyPassword.
val fichierSignature = File(System.getProperty("user.home"), ".sonne/keystore.properties")
val proprietesSignature = Properties().apply {
    if (fichierSignature.exists()) fichierSignature.inputStream().use { load(it) }
}

// Date of the latest internal security review: the newest docs/security/YYYY-MM-DD.md, shown in About.
val derniereRevue: String = rootProject.file("../docs/security")
    .listFiles { f -> f.name.matches(Regex("""\d{4}-\d{2}-\d{2}\.md""")) }
    ?.maxOfOrNull { it.name.removeSuffix(".md") }
    ?: error("docs/security/ has no dated review (YYYY-MM-DD.md)")

/**
 * Copies the repository's CHANGELOG.md and PRIVACY.md into the app's assets (What's new, Privacy policy),
 * and the licenses of the embedded fonts (SIL Open Font License, shipped with the fonts).
 */
abstract class CopieDocuments : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val sortie: DirectoryProperty

    @TaskAction
    fun copier() {
        val dossier = sortie.get().asFile
        dossier.deleteRecursively()
        dossier.mkdirs()
        sources.files.forEach { it.copyTo(File(dossier, it.name), overwrite = true) }
    }
}

val copieDocuments = tasks.register<CopieDocuments>("copieDocuments") {
    sources.from(
        rootProject.file("../CHANGELOG.md"), rootProject.file("../PRIVACY.md"),
        rootProject.file("fonts/OFL-BricolageGrotesque.txt"), rootProject.file("fonts/OFL-Figtree.txt"),
    )
}

androidComponents {
    onVariants { variante ->
        variante.sources.assets?.addGeneratedSourceDirectory(copieDocuments, CopieDocuments::sortie)
    }
}

android {
    namespace = "dev.sidali.sonne"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.sidali.sonne"
        minSdk = 31
        // targetSdk 34 voulu (distribution hors Play, par Obtainium) : à partir de 35, Android 15
        // et plus interdisent à l'app de lever le mode Ne pas déranger global (compat change
        // MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES), et la sonnerie resterait muette en
        // « Aucune interruption ». compileSdk 36 reste. Voir docs/PROTOCOL.md et android/README.md.
        targetSdk = 34
        // Surchargeables pour les tests de bout en bout de la mise à jour intégrée seulement
        // (docs/END-TO-END-TEST.md) : -PsonneVersionCode=5 -PsonneVersionName=1.2.1
        versionCode = (findProperty("sonneVersionCode") as String?)?.toInt() ?: 12
        versionName = (findProperty("sonneVersionName") as String?) ?: "1.7.0"
        buildConfigField("String", "SECURITY_REVIEW", "\"$derniereRevue\"")
        // Commandes v2 signées encore acceptées et envoyées (transition de la 1.6, voir
        // docs/PROTOCOL.md, « Transition, then cut-off of the old format »). Passe à false
        // pour l'app 1.8.0, jamais avant que le relais et la page de la coupure soient déployés
        // (la 1.7.0 n'a changé que l'apparence).
        // -PsonneCoupureV2=true construit une version coupée, pour les tests de bout en bout seulement.
        val coupureV2 = (findProperty("sonneCoupureV2") as String?)?.toBoolean() ?: false
        buildConfigField("boolean", "ACCEPTER_V2", (!coupureV2).toString())
    }

    signingConfigs {
        if (fichierSignature.exists()) {
            create("release") {
                storeFile = file(proprietesSignature.getProperty("storeFile"))
                storePassword = proprietesSignature.getProperty("storePassword")
                keyAlias = proprietesSignature.getProperty("keyAlias")
                keyPassword = proprietesSignature.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Reproducible builds: no git commit id baked into the APK.
            vcsInfo.include = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo { includeInApk = false; includeInBundle = false }

    // English only: library translations are dropped too, so no screen mixes two languages.
    androidResources { localeFilters += listOf("en") }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.credentials:credentials:1.5.0")

    testImplementation("junit:junit:4.13.2")
    // Implémentation JVM de org.json (celle d'Android n'existe pas hors de l'appareil) : les
    // tests soumettent au filtre les messages exacts de docs/VECTEURS.json. Absente de l'APK.
    testImplementation("org.json:json:20240303")
}
