// Copyright 2026 The Lava Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

//go:build linux && amd64

package libc // import "modernc.org/libc"

import "golang.org/x/sys/unix"

// Lava F3 fix (2026-07-04) — Android x86_64 app-seccomp legacy-syscall remap.
//
// Android's x86_64 application-seccomp allowlist BLOCKS the legacy path-based
// Linux syscalls (lstat=6, stat=4, open=2, access=21, readlink=89, unlink=87,
// rmdir=84, mkdir=83, chmod=90, chown=92, lchown=94, rename=82, link=86,
// symlink=88, mknod=133). A process that issues one gets SIGSYS (Bad system
// call) and dies. modernc/libc's ccgo-translated musl for linux/amd64 emits
// those legacy syscalls directly in its stat/open/... fast paths (see
// _fstatat_kstat in ccgo_linux_amd64.go), so the embedded liblavaapi.so
// crashed on the first SQLite/TLS file op inside the api-app on the x86_64
// emulator. arm64 has no legacy stat/open syscalls — musl there already emits
// the *at forms — which is why real arm64 devices were never affected.
//
// remapLegacyPathSyscall rewrites each blocked legacy call into its *at
// equivalent (newfstatat/openat/faccessat/...), which the allowlist PERMITS.
// The legacy call carries a leading path; the *at form inserts AT_FDCWD (-100)
// as the dir-fd and, where applicable, a trailing flag — semantically
// identical, and exactly what musl emits natively on arm64. It is the single
// choke point: every ccgo call site funnels through the X__syscallN
// dispatchers in syscall_musl.go, which call this first.
//
// Returns (result, true) when it handled n; (0, false) to let the caller
// issue n unchanged.
func remapLegacyPathSyscall(n, a1, a2, a3 long) (long, bool) {
	const atFDCWD = -100
	switch n {
	case SYS_lstat: // (path, buf) -> newfstatat(AT_FDCWD, path, buf, AT_SYMLINK_NOFOLLOW)
		return seccompAt(SYS_newfstatat, atFDCWD, a1, a2, AT_SYMLINK_NOFOLLOW, 0, 0), true
	case SYS_stat: // (path, buf) -> newfstatat(AT_FDCWD, path, buf, 0)
		return seccompAt(SYS_newfstatat, atFDCWD, a1, a2, 0, 0, 0), true
	case SYS_open: // (path, flags [,mode]) -> openat(AT_FDCWD, path, flags, mode)
		return seccompAt(SYS_openat, atFDCWD, a1, a2, a3, 0, 0), true
	case SYS_access: // (path, mode) -> faccessat(AT_FDCWD, path, mode, 0)
		return seccompAt(SYS_faccessat, atFDCWD, a1, a2, 0, 0, 0), true
	case SYS_readlink: // (path, buf, bufsz) -> readlinkat(AT_FDCWD, path, buf, bufsz)
		return seccompAt(SYS_readlinkat, atFDCWD, a1, a2, a3, 0, 0), true
	case SYS_unlink: // (path) -> unlinkat(AT_FDCWD, path, 0)
		return seccompAt(SYS_unlinkat, atFDCWD, a1, 0, 0, 0, 0), true
	case SYS_rmdir: // (path) -> unlinkat(AT_FDCWD, path, AT_REMOVEDIR)
		return seccompAt(SYS_unlinkat, atFDCWD, a1, AT_REMOVEDIR, 0, 0, 0), true
	case SYS_mkdir: // (path, mode) -> mkdirat(AT_FDCWD, path, mode)
		return seccompAt(SYS_mkdirat, atFDCWD, a1, a2, 0, 0, 0), true
	case SYS_chmod: // (path, mode) -> fchmodat(AT_FDCWD, path, mode, 0)
		return seccompAt(SYS_fchmodat, atFDCWD, a1, a2, 0, 0, 0), true
	case SYS_chown: // (path, owner, group) -> fchownat(AT_FDCWD, path, owner, group, 0)
		return seccompAt(SYS_fchownat, atFDCWD, a1, a2, a3, 0, 0), true
	case SYS_lchown: // (path, owner, group) -> fchownat(AT_FDCWD, path, owner, group, AT_SYMLINK_NOFOLLOW)
		return seccompAt(SYS_fchownat, atFDCWD, a1, a2, a3, AT_SYMLINK_NOFOLLOW, 0), true
	case SYS_rename: // (old, new) -> renameat(AT_FDCWD, old, AT_FDCWD, new)
		return seccompAt(SYS_renameat, atFDCWD, a1, atFDCWD, a2, 0, 0), true
	case SYS_link: // (old, new) -> linkat(AT_FDCWD, old, AT_FDCWD, new, 0)
		return seccompAt(SYS_linkat, atFDCWD, a1, atFDCWD, a2, 0, 0), true
	case SYS_symlink: // (target, linkpath) -> symlinkat(target, AT_FDCWD, linkpath)
		return seccompAt(SYS_symlinkat, a1, atFDCWD, a2, 0, 0, 0), true
	case SYS_mknod: // (path, mode, dev) -> mknodat(AT_FDCWD, path, mode, dev)
		return seccompAt(SYS_mknodat, atFDCWD, a1, a2, a3, 0, 0), true
	}
	return 0, false
}

// seccompAt issues one syscall via unix.Syscall6 and returns the musl-style
// result (negative errno on failure, the raw return otherwise). Trailing args
// beyond the target syscall's arity are 0 and ignored by the kernel.
func seccompAt(n, a1, a2, a3, a4, a5, a6 long) long {
	r1, _, err := unix.Syscall6(uintptr(n), uintptr(a1), uintptr(a2), uintptr(a3), uintptr(a4), uintptr(a5), uintptr(a6))
	if err != 0 {
		return long(-err)
	}
	return long(r1)
}
