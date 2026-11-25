package com.kakario.liverecorder

import android.content.Context
import java.io.File

/**
 * 보류함: 지금은 처리하지 않고 따로 둔 파일 (계속 끊기는 큰 파일, 업로드 안 된 채 휴지통에 들어간 파일 등).
 * 정리·변환·업로드·휴지통 비우기 어디서도 건드리지 않음. 나중에 '되돌리기'로 다시 줄에 넣음.
 */
object Hold {
    fun dir(): File = File(Uploader.trashDir().parentFile, "보류함").apply {
        mkdirs()
        File(this, ".nomedia").let { if (!it.exists()) try { it.createNewFile() } catch (_: Exception) {} }
    }
    fun files(): List<File> = dir().listFiles()?.filter { it.isFile && it.name != ".nomedia" }?.sortedBy { it.length() } ?: emptyList()

    /** 파일(과 같은 이름의 채팅 자막 등)을 보류함으로 옮김 */
    fun put(f: File): Boolean {
        val base = f.nameWithoutExtension
        f.parentFile?.listFiles()?.filter { it != f && it.isFile && it.name.startsWith("$base.") }?.forEach { it.renameTo(File(dir(), it.name)) }
        return f.renameTo(File(dir(), f.name))
    }

    /** 보류함에서 꺼내 원래 폴더로 → 자동 정리가 다시 줄에 넣음 */
    fun restore(ctx: Context, f: File): Boolean {
        val dst = File(Uploader.trashDir().parentFile, f.name)
        val base = f.nameWithoutExtension
        dir().listFiles()?.filter { it != f && it.name.startsWith("$base.") }?.forEach { it.renameTo(File(dst.parentFile, it.name)) }
        val ok = f.renameTo(dst)
        if (ok) { RecordService.addLog("보류함에서 되돌림: ${f.name}"); Tidy.sweep(ctx) }
        return ok
    }
}
