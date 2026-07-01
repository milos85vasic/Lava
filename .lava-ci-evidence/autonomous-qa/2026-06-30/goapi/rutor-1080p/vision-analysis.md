# Vision analysis — rutor-1080p

- Generated: 2026-06-29T21:16:17Z
- Iteration dir: `.lava-ci-evidence/autonomous-qa/2026-06-30/goapi/rutor-1080p`
- Tools: ffmpeg=`/home/milosvasic/bin/ffmpeg`; tesseract=`/home/milosvasic/.local/bin/tesseract` (langs: eng,osd,rus); imagemagick=absent
- Heuristics: uniform-frame = YMAX-YMIN ≤ 40; blank defect = ≥5 consecutive uniform frames OR ≥50% uniform; frozen defect = freezedetect span ≥10s.
- JUnit cross-ref (verdict.json): ?, failures=1, errors=0

## Recordings

### `qa_rec_0.mp4`
- frames sampled (@1fps): **136**
- uniform/blank frames: **4** (2%) — white=1 black=3 mono/colored=0; longest consecutive blank run: **3** frame(s)
- frozen spans ≥10s (freezedetect): **1** (longest 83.1s)
- 🚩 **DEFECT (§6.AB stuck/frozen screen):** 1 frozen span(s), longest 83.1s — UI not progressing. (Note: an *animated* spinner stays animated and is NOT caught here; see OCR section.)
- OCR (tesseract, langs: eng,osd,rus): text recognized on sampled frames: yes
  - expected affordance tokens found: (none)
  - expected affordance tokens MISSING: download, magnet, torrent, seed, size
- 🚩 **DEFECT (§6.AB wrong screen / missing download affordance, OCR):** none of the expected affordances (download,magnet,torrent,seed,size) appeared on any sampled frame.

## Logcat

- logcat: **52 crash/ANR marker line(s) found**:
```
43116:--------- beginning of crash
43117:06-29 21:06:14.925  6532  6532 E AndroidRuntime: FATAL EXCEPTION: main
43118:06-29 21:06:14.925  6532  6532 E AndroidRuntime: Process: digital.vasic.lava.client.dev, PID: 6532
43119:06-29 21:06:14.925  6532  6532 E AndroidRuntime: java.lang.RuntimeException: Unable to destroy activity {digital.vasic.lava.client.dev/digital.vasic.lava.client.MainActivity}: java.lang.IllegalStateException: State must be at least 'CREATED' to be moved to 'DESTROYED' in component NavBackStackEntry(17776e22-f695-40e4-a5e8-c6faa3ad7269) destination=Destination(0xe36e02dd) route=search/search_input?query={query}&categories={categories}&author_name={author_name}&author_id={author_id}&sort={sort}&order={order}&period={period}
43120:06-29 21:06:14.925  6532  6532 E AndroidRuntime: 	at android.app.ActivityThread.performDestroyActivity(ActivityThread.java:5613)
43121:06-29 21:06:14.925  6532  6532 E AndroidRuntime: 	at android.app.ActivityThread.handleDestroyActivity(ActivityThread.java:5645)
43122:06-29 21:06:14.925  6532  6532 E AndroidRuntime: 	at android.app.servertransaction.DestroyActivityItem.execute(DestroyActivityItem.java:47)
43123:06-29 21:06:14.925  6532  6532 E AndroidRuntime: 	at android.app.servertransaction.ActivityTransactionItem.execute(ActivityTransactionItem.java:45)
43124:06-29 21:06:14.925  6532  6532 E AndroidRuntime: 	at android.app.servertransaction.TransactionExecutor.executeLifecycleState(TransactionExecutor.java:180)
43125:06-29 21:06:14.925  6532  6532 E AndroidRuntime: 	at android.app.servertransaction.TransactionExecutor.execute(TransactionExecutor.java:98)
43126:06-29 21:06:14.925  6532  6532 E AndroidRuntime: 	at android.app.ActivityThread$H.handleMessage(ActivityThread.java:2443)
43127:06-29 21:06:14.925  6532  6532 E AndroidRuntime: 	at android.os.Handler.dispatchMessage(Handler.java:106)
43128:06-29 21:06:14.925  6532  6532 E AndroidRuntime: 	at android.os.Looper.loopOnce(Looper.java:205)
43129:06-29 21:06:14.925  6532  6532 E AndroidRuntime: 	at android.os.Looper.loop(Looper.java:294)
43130:06-29 21:06:14.925  6532  6532 E AndroidRuntime: 	at android.app.ActivityThread.main(ActivityThread.java:8177)
43131:06-29 21:06:14.925  6532  6532 E AndroidRuntime: 	at java.lang.reflect.Method.invoke(Native Method)
43132:06-29 21:06:14.925  6532  6532 E AndroidRuntime: 	at com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:552)
43133:06-29 21:06:14.925  6532  6532 E AndroidRuntime: 	at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:971)
43134:06-29 21:06:14.925  6532  6532 E AndroidRuntime: Caused by: java.lang.IllegalStateException: State must be at least 'CREATED' to be moved to 'DESTROYED' in component NavBackStackEntry(17776e22-f695-40e4-a5e8-c6faa3ad7269) destination=Destination(0xe36e02dd) route=search/search_input?query={query}&categories={categories}&author_name={author_name}&author_id={author_id}&sort={sort}&order={order}&period={period}
43135:06-29 21:06:14.925  6532  6532 E AndroidRuntime: 	at androidx.lifecycle.LifecycleRegistryKt.checkLifecycleStateTransition(LifecycleRegistry.kt:92)
--- context (lines mentioning digital.vasic.lava.client.dev around first crash) ---
104:06-29 21:04:14.425  1753  1789 D MediaGrants: Removed 0 media_grants for 0 user for digital.vasic.lava.client.dev. Reason: Mode changed: android:read_external_storage
321:06-29 21:04:14.756  1753  1789 D MediaGrants: Removed 0 media_grants for 0 user for digital.vasic.lava.client.dev. Reason: Mode changed: android:read_external_storage
1427:06-29 21:04:16.123   549   890 I SdkSandboxManager: No SDKs used. Skipping SDK data reconcilation for CallingInfo{mUid=10192, mPackageName='digital.vasic.lava.client.dev, mAppProcessToken='null'}
1939:06-29 21:04:17.519   549   605 I AppsFilter: interaction: PackageSetting{e39f85e digital.vasic.lava.client.dev/10192} -> PackageSetting{f0f943f com.google.android.youtube/10139} BLOCKED
1940:06-29 21:04:17.519   549   605 I AppsFilter: interaction: PackageSetting{e39f85e digital.vasic.lava.client.dev/10192} -> PackageSetting{87810c com.google.android.googlequicksearchbox/10132} BLOCKED
1941:06-29 21:04:17.519   549   605 I AppsFilter: interaction: PackageSetting{e39f85e digital.vasic.lava.client.dev/10192} -> PackageSetting{e4bca55 com.google.android.as/10126} BLOCKED
1942:06-29 21:04:17.519   549   605 I AppsFilter: interaction: PackageSetting{e39f85e digital.vasic.lava.client.dev/10192} -> PackageSetting{a93c6a com.google.android.gm/10143} BLOCKED
1943:06-29 21:04:17.519   549   605 I AppsFilter: interaction: PackageSetting{e39f85e digital.vasic.lava.client.dev/10192} -> PackageSetting{c73355b com.google.android.devicelockcontroller/10172} BLOCKED
```

## Gradle connected log

- failure/exception marker lines: 3 (cross-reference only; JUnit verdict is authoritative)

## Verdict

**DEFECTS-FOUND** (3):
- FROZEN-SCREEN: qa_rec_0.mp4 — 1 span(s), longest 83.1s
- OCR-NO-AFFORDANCE: qa_rec_0.mp4 — none of [download,magnet,torrent,seed,size] seen
- CRASH: 52 crash/ANR marker line(s) in logcat.txt
