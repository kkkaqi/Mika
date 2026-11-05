package com.wangzan101.mika.core.shell

/** 在 su 的执行端监督命令。标准输入留给 App，关闭时结束这次命令的进程组。 */
object RootCommandSession {
    val androidScript = supervise("/system/bin/sh", "/system/bin/setsid", "/system/bin/kill")

    /** 调用方把命令放在变量 c 中。旧版 setsid 会 fork，监督脚本在新会话内取得实际组号。 */
    internal fun supervise(shell: String, setsid: String, kill: String): String = """
        exec $setsid -w $shell -c '
        set +m
        group=${'$'}${'$'}
        exec 3<&0
        $shell -c "${'$'}1" </dev/null 3<&- &
        child=${'$'}!
        (
            IFS= read -r stop <&3
            $kill -s KILL -- "-${'$'}group" 2>/dev/null
        ) &
        watcher=${'$'}!
        exec 3<&-
        wait "${'$'}child"
        status=${'$'}?
        $kill -s KILL "${'$'}watcher" 2>/dev/null
        wait "${'$'}watcher" 2>/dev/null
        exit "${'$'}status"
        ' mika-root "${'$'}c"
    """.trimIndent()
}
