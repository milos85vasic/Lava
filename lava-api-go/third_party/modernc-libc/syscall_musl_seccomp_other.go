// Copyright 2026 The Lava Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

//go:build linux && (arm64 || arm || 386 || loong64 || ppc64le || s390x || riscv64)

package libc // import "modernc.org/libc"

// No-op remap for the non-amd64 musl arches. arm64/riscv64/loong64/ppc64le/
// s390x have no legacy path-based stat/open syscalls at all — musl there emits
// the *at forms natively, so there is nothing for Android's seccomp to block
// and nothing to remap (this is why real arm64 devices never hit the F3
// SIGSYS). arm/386 keep their upstream syscall behavior; they are not x86_64
// gate targets and their legacy-syscall consts are left untouched here. See
// syscall_musl_seccomp_amd64.go for the amd64 remap and the full rationale.
func remapLegacyPathSyscall(n, a1, a2, a3 long) (long, bool) {
	return 0, false
}
