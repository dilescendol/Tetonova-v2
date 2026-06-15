package com.tetonova.core.model

/**
 * Sample catalog/forum/profile data ported verbatim from the design prototype
 * (app/data.jsx, home.jsx). Stands in for the real repo-tn catalog / API until the
 * data layer is wired. Indonesian copy is intentional (the app UI is Indonesian).
 */
object SampleData {

    val posters = listOf(
        PosterItem("Throne of Seal", "S4 · Ep 12", art = 1, badge = "Donghua", ep = "12", progress = 80),
        PosterItem("Soul Reaper Saga", "S2 · Ep 8", art = 2, badge = "Donghua", ep = "8", progress = 45),
        PosterItem("Sword & Fairy 3", "Ep 24", art = 4, badge = "Movie", ep = "24", progress = 100),
        PosterItem("Jade Dynasty", "Ep 5", art = 6, badge = "Donghua", ep = "5", progress = 20),
        PosterItem("Stellar Ascension", "Ep 16", art = 3, badge = "Donghua", ep = "16", progress = 62),
        PosterItem("Frost Lotus", "Ep 2", art = 5, badge = "Drakor", ep = "2", progress = 10),
    )

    val followed = listOf(
        PosterItem("Throne of Seal", "Ongoing", art = 1, badge = "Donghua"),
        PosterItem("Jade Dynasty", "Ongoing", art = 6, badge = "Donghua"),
        PosterItem("Stellar Ascension", "Ongoing", art = 3, badge = "Donghua"),
        PosterItem("Frost Lotus", "12 ep", art = 5, badge = "Drakor"),
    )

    val downloads = listOf(
        PosterItem("Sword & Fairy 3", "Ep 24 · 480MB", art = 4, badge = "HD"),
        PosterItem("Soul Reaper Saga", "Ep 8 · 320MB", art = 2, badge = "HD"),
    )

    val spots = listOf(
        SpotItem(
            "Throne of Seal", "Sword & Soul Saga",
            "Long Haochen mewarisi takdir Para Petarung Cahaya. Saat segel para iblis melemah, ia harus menemukan kekuatannya — dan musuh tak terduga di antara sekutu sendiri.",
            listOf(SpotTag("flame2", "Donghua"), SpotTag("star", "S4"), SpotTag("zap", "Action"), SpotTag("sparkle", "Fantasy")),
            "8.9", "24 ep", "Ongoing", 1,
        ),
        SpotItem(
            "Soul Reaper Saga", "Pengembara Roh",
            "Setelah kematian gurunya, Lin Xuan memulai pengembaraan mencari pewaris sejati klan reaper. Diburu enam sekte sekaligus, ia menemukan kebenaran yang lebih gelap.",
            listOf(SpotTag("flame2", "Donghua"), SpotTag("star", "S2"), SpotTag("zap", "Action"), SpotTag("shield", "Cultivation")),
            "8.6", "18 ep", "Ongoing", 5,
        ),
        SpotItem(
            "Stellar Ascension", "Pendaki Langit",
            "Di akademi cultivation paling elit, satu murid biasa menemukan teknik yang dilupakan zaman. Saingannya bukan teman sebaya — melainkan langit itu sendiri.",
            listOf(SpotTag("flame2", "Donghua"), SpotTag("sparkle", "Fantasy"), SpotTag("zap", "Action")),
            "8.4", "16 ep", "Ongoing", 3,
        ),
    )

    val homeQuickChips = listOf(
        QuickChip("Lite Mode", "Off", "zap", 0),
        QuickChip("Data Saver", "Balanced", "layers", 4),
        QuickChip("Source", "Semua", "globe", 6),
    )

    val searchTags = listOf(
        "Cultivation", "Ongoing 2025", "Completed", "Donghua", "Movie", "Action", "Romance", "Fantasy",
    )

    val extCategories = listOf(
        ExtCategory("anime", "Anime/Donghua", 14),
        ExtCategory("movie", "Movie", 3),
        ExtCategory("drama", "Dracin/Drakor", 23),
    )
    val extItems = listOf(
        ExtItem("Anichin", "Lokal", false),
        ExtItem("AnimeSail", "Live", true),
        ExtItem("AnimeXin", "Lokal", false),
        ExtItem("Anixverse", "Lokal", false),
        ExtItem("Kuramanime", "Live", true),
        ExtItem("Otakudesu", "Lokal", false),
    )

    val forumCategories = listOf(
        ForumCategory("all", "Semua", "globe"),
        ForumCategory("discussion", "Diskusi"),
        ForumCategory("recommend", "Rekomendasi"),
        ForumCategory("spoiler", "Spoiler Zone"),
        ForumCategory("help", "Bantuan"),
        ForumCategory("fanart", "Fan Art"),
    )

    val threads = listOf(
        ForumThread(1, true, "discussion", "Aturan Forum & Etika Diskusi TetoNova — Wajib Baca",
            "Selamat datang di komunitas! Sebelum posting, baca panduan singkat soal spoiler tag, sumber, dan cara request donghua. Mari jaga forum tetap asik buat semua.",
            "Kireina", "mod", "Dipin · 2 hari", 412, 38, "12k", listOf(TagChip("Pengumuman", "grape")), 0),
        ForumThread(2, false, "discussion", "Throne of Seal S4 Ep 12 — plot twist-nya gila sih, ada yang sadar foreshadowing-nya?",
            "Pas adegan di kuil itu, kalau diperhatiin dari ep 3 udah ada clue. Animasinya juga naik level banget episode ini. Spoiler di dalam ya!",
            "rafzhx", "op", "3 jam lalu", 184, 56, "4.2k", listOf(TagChip("Spoiler", "spoiler"), TagChip("Donghua")), 1, thumb = true),
        ForumThread(3, false, "recommend", "Rekomen donghua cultivation yang animasinya halus dong, abis nonton Soul Land",
            "Bosan nunggu episode baru. Pengen yang world-building-nya solid sama fight scene rame. Budget bebas mau yang ongoing atau completed.",
            "mochiko", null, "6 jam lalu", 97, 41, "2.8k", listOf(TagChip("Rekomendasi"), TagChip("Cultivation")), 2),
        ForumThread(4, false, "help", "Source AnimeSail sering buffering di Android TV, ada fix?",
            "Di HP lancar tapi di TV suka mandek pas 720p. Udah coba Data Saver balanced tetep. Mungkin ada yang punya setting optimal?",
            "budi_nonton", null, "Kemarin", 53, 19, "1.5k", listOf(TagChip("Bantuan", "coral"), TagChip("Android TV")), 3),
        ForumThread(5, false, "fanart", "Iseng gambar ulang poster Sword & Fairy 3 versi sketsa — mohon kritiknya",
            "Latihan lighting dan pose dinamis. Ini masih WIP, line art-nya belum dirapihin. Feedback buat anatomi tangan paling dibutuhin hehe.",
            "pensil_basah", null, "Kemarin", 231, 27, "5.1k", listOf(TagChip("Fan Art", "grape")), 4, thumb = true),
        ForumThread(6, false, "discussion", "Tier list donghua 2025 versi kalian gimana? Aku bikin draft",
            "Lagi nyusun tier list buat yang rilis tahun ini. S-tier sementara isinya 3 judul. Pengen denger argumen kalian sebelum aku finalize.",
            "tierlist_addict", null, "2 hari lalu", 142, 88, "7.3k", listOf(TagChip("Diskusi"), TagChip("2025")), 5),
    )

    val replies = listOf(
        ForumReply(1, "Kireina", "mod", "2 jam lalu", 64,
            "Foreshadowing-nya emang rapih. Di ep 3 simbol di gerbang kuil itu sama persis sama yang muncul pas twist. Studio-nya niat banget detailnya.", false),
        ForumReply(2, "silverfox", null, "1 jam lalu", 28,
            "Bener! Aku malah baru ngeh pas baca komen ini. Mau rewatch dari awal ah, pasti banyak yang kelewat.", true),
        ForumReply(3, "anya_w", null, "48 menit lalu", 19,
            "Animasi fight scene-nya frame-by-frame mulus. Kayaknya budget season ini naik signifikan dibanding S3.", false),
        ForumReply(4, "rafzhx", "op", "30 menit lalu", 12,
            "Setuju semua. Btw next ep katanya bakal ada flashback antagonis, makin penasaran motivasi dia.", false),
    )

    val stats = listOf(
        StatItem("Jam nonton", "248", "+12", "play", 0),
        StatItem("Episode", "1.3k", "+24", "eye", 6),
        StatItem("Hari streak", "37", null, "flame2", 4),
        StatItem("Judul selesai", "62", "+3", "check", 2),
    )

    val badges = listOf(
        BadgeItem("Marathoner", "10 ep/hari", "play", 0, false),
        BadgeItem("Night Owl", "Nonton 2am", "star", 1, false),
        BadgeItem("Streak 30", "30 hari", "flame2", 4, false),
        BadgeItem("Kritikus", "50 review", "comment", 3, false),
        BadgeItem("Kolektor", "100 followed", "bookmark", 6, true),
        BadgeItem("Legenda", "Lv.50", "trophy", 7, true),
    )

    val rewards = listOf(
        RewardItem("Lv.1", "Starter Kit", "sparkle", 2, RewardState.CLAIMED),
        RewardItem("Lv.5", "Rose Frame", "frame", 0, RewardState.NEXT),
        RewardItem("Lv.10", "Banner Pack", "banner", 6, RewardState.LOCKED),
        RewardItem("Lv.20", "Neon Theme", "palette", 1, RewardState.LOCKED),
        RewardItem("Lv.50", "Golden Crown", "trophy", 4, RewardState.LOCKED),
    )

    /** Stable avatar gradient index from a username. */
    fun avatarIndex(name: String): Int {
        var s = 0
        for (c in name) s += c.code
        return s % 8
    }
}
