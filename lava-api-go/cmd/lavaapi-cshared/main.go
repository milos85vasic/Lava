// Command lavaapi-cshared builds the in-process lava-api-go embed into an
// Android-loadable native library via `go build -buildmode=c-shared`.
//
// Why c-shared + JNI (not gomobile bind):
//
//	gomobile bind is BLOCKED on this module — lava-api-go's go.mod uses
//	relative `replace ../submodules/*` directives, which gomobile's overlay-
//	module generator cannot resolve (proven). `go build -buildmode=c-shared`
//	uses ordinary module resolution, so it HONORS the replace directives. The
//	tradeoff is that c-shared exports a flat C ABI, not a generated Java class
//	hierarchy — so a hand-written JNI bridge (jni/jni_bridge.c) adapts the four
//	exported C functions below to the JNI naming convention the Kotlin side
//	(Phase C) binds via `external fun`. The JNI bridge + its CMakeLists live in
//	the jni/ subdirectory (NOT this package) so the cgo build does not try to
//	compile jni_bridge.c — that file #includes the liblavaapi.h header THIS
//	build produces, so it is compiled later by the Android NDK/CMake side.
//
// Exported C surface (all string args/returns are NUL-terminated UTF-8; every
// returned *C.char is heap-allocated by C.CString and MUST be released by the
// caller via LavaApiFree):
//
//	LavaApiStart(configJSON) -> ""  on success, or the error message
//	LavaApiStop()            -> ""  on success, or the error message
//	LavaApiStatus()          -> the Status JSON document
//	LavaApiFree(p)            free a string previously returned by this library
//
// The underlying lifecycle is digital.vasic.lava.apigo/internal/mobile, which
// serves the FULL production Gin router over TLS, SQLite-backed. See that
// package's doc comment for the served-API and TLS details.
//
// Decoupled Reusable rationale: this is Lava-domain glue (the c-shared entry +
// the C-ABI adaptation of the embed's Start/Stop/Status surface). It introduces
// no logic another vasic-digital project would consume directly.
package main

// #include <stdlib.h>
import "C"

import (
	"unsafe"

	"digital.vasic.lava.apigo/internal/mobile"
)

// errString converts an error to the C-ABI string contract: empty string on
// success (nil error), the error message otherwise. The returned *C.char is
// allocated by C.CString and is owned by the caller (free via LavaApiFree).
func errString(err error) *C.char {
	if err == nil {
		return C.CString("")
	}
	return C.CString(err.Error())
}

//export LavaApiStart
//
// LavaApiStart parses the config JSON and starts the embedded server. Returns
// an empty C string on success, or the error message. The caller owns the
// returned string and MUST release it with LavaApiFree.
func LavaApiStart(configJSON *C.char) *C.char {
	return errString(mobile.Start(C.GoString(configJSON)))
}

//export LavaApiStop
//
// LavaApiStop gracefully stops the embedded server. Returns an empty C string
// on success, or the error message. The caller owns the returned string and
// MUST release it with LavaApiFree.
func LavaApiStop() *C.char {
	return errString(mobile.Stop())
}

//export LavaApiStatus
//
// LavaApiStatus returns the server-state JSON document (never errors). The
// caller owns the returned string and MUST release it with LavaApiFree.
func LavaApiStatus() *C.char {
	return C.CString(mobile.Status())
}

//export LavaApiFree
//
// LavaApiFree releases a string previously returned by LavaApiStart,
// LavaApiStop, or LavaApiStatus. Passing nil is a no-op. Passing any other
// pointer not obtained from this library is undefined behavior.
func LavaApiFree(p *C.char) {
	if p != nil {
		C.free(unsafe.Pointer(p))
	}
}

// main is required for buildmode=c-shared but is never executed when the .so is
// dlopen'd by the Android runtime.
func main() {}
