plugins {
    id("lava.android.feature")
    id("lava.android.library.compose")
}

android {
    namespace = "lava.topic"
}

dependencies {
    // LVA-052 — the topic download action branches on ProviderDownloadKind
    // (HTTP-file vs .torrent) resolved via ResolveProviderDownloadKindUseCase.
    // The enum is the domain-safe projection that lives in core:network:api
    // (no core:tracker:* leak); the feature consumes only the enum.
    implementation(project(":core:network:api"))

    // LVA-052 — the real-stack ViewModel test wires the REAL
    // DownloadHttpFileUseCase + ResolveProviderDownloadKindUseCase. Those need
    // the network seams (HttpDownloadSource / ProviderCapabilitySource) and the
    // downloads request/service types on the test classpath; the boundary fakes
    // below the use cases live here too.
    testImplementation(project(":core:downloads"))
    testImplementation(project(":core:dispatchers"))
    testImplementation(project(":core:auth:api"))
}
