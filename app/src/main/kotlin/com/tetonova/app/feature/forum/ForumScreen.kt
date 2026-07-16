package com.tetonova.app.feature.forum

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetonova.app.data.FPost
import com.tetonova.app.data.FThread
import com.tetonova.app.data.TnData
import com.tetonova.app.ui.PageScroll
import com.tetonova.app.ui.TnPrimaryButton
import com.tetonova.app.ui.bleedEnd
import com.tetonova.core.designsystem.Avatar
import com.tetonova.core.designsystem.Pill
import com.tetonova.core.designsystem.SectionHead
import com.tetonova.core.designsystem.TnCard
import com.tetonova.core.designsystem.TnChip
import com.tetonova.core.designsystem.TnIcon
import com.tetonova.core.designsystem.theme.HeroGradientColors
import com.tetonova.core.designsystem.theme.TnRadii
import com.tetonova.core.designsystem.theme.TnTheme
import com.tetonova.core.designsystem.tnGradient
import com.tetonova.core.model.ForumCategory
import com.tetonova.core.model.ForumReply
import com.tetonova.core.model.ForumThread
import com.tetonova.core.model.TagChip
import kotlinx.coroutines.launch

/** The user's handle for posts/votes — the same synced username shown on the Profile @handle. */
private val CURRENT_USER: String get() = TnData.profileUsername

/** Forum categories — must match the panel's accepted set (PublicApi::isForumCategory). */
private val FORUM_CATS = listOf(
    ForumCategory("all", "Semua", "globe"),
    ForumCategory("umum", "Umum", "comment"),
    ForumCategory("tanya", "Tanya", "help"),
    ForumCategory("request-source", "Request", "sparkle"),
    ForumCategory("bug", "Bug", "shield"),
    ForumCategory("off-topic", "Off-topic", "star"),
    ForumCategory("appeal", "Banding", "info"),
)

@Composable
fun ForumScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Threads now come from the panel forum API (shared, server-authoritative). XP is awarded
    // server-side on clean posts/replies/upvotes; we refresh the cultivation XP after each action.
    var threads by remember { mutableStateOf<List<ForumThread>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var openThread by remember { mutableStateOf<ForumThread?>(null) }
    var composing by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf("new") }
    var category by remember { mutableStateOf("all") }
    val voted = remember { mutableStateOf(setOf<Int>()) }

    val installId = TnData.deviceInstallId
    val level = TnData.userXp?.level ?: 1
    val xpInto = TnData.userXp?.xpIntoLevel ?: 0
    fun panelSort() = when (sort) { "new" -> "newest"; "top" -> "top"; else -> "hot" }

    suspend fun reload() {
        loading = true
        threads = TnData.forumApi()?.listThreads(panelSort(), category, installId)?.map { it.toModel() } ?: emptyList()
        loading = false
    }
    LaunchedEffect(sort, category) { reload() }

    // Vote (toggle): up → clear. Upvotes credit the thread AUTHOR's XP server-side; the optimistic
    // toggle gives instant feedback, the real tally lands on the next reload.
    fun doVote(t: ForumThread) {
        val already = voted.value.contains(t.id)
        scope.launch {
            val r = TnData.forumApi()?.vote(t.id, installId, CURRENT_USER, if (already) "clear" else "up", level, xpInto)
            if (r?.success == true) voted.value = voted.value.toggle(t.id)
            else r?.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
        }
    }

    val thread = openThread
    if (thread != null) {
        BackHandler { openThread = null }
        ThreadDetail(
            thread = thread,
            voted = voted.value.contains(thread.id),
            onVote = { doVote(thread) },
            onBack = { openThread = null },
            onReply = { text ->
                scope.launch {
                    val r = TnData.forumApi()?.createPost(thread.id, installId, CURRENT_USER, text, level, xpInto)
                    r?.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
                    if (r?.success == true) {
                        TnData.refreshUserXp()
                        TnData.forumApi()?.getThread(thread.id, installId)?.let { (t, posts) -> openThread = t.toModel(posts) }
                    }
                }
            },
            onDelete = {
                scope.launch {
                    val r = TnData.forumApi()?.deleteThread(thread.id, installId)
                    if (r?.success == true) { openThread = null; reload() }
                    else Toast.makeText(ctx, r?.message ?: "Gagal menghapus", Toast.LENGTH_SHORT).show()
                }
            },
            onDeleteComment = { replyId ->
                scope.launch {
                    val r = TnData.forumApi()?.deletePost(replyId, installId)
                    if (r?.success == true) {
                        TnData.forumApi()?.getThread(thread.id, installId)?.let { (t, posts) -> openThread = t.toModel(posts) }
                    } else Toast.makeText(ctx, r?.message ?: "Gagal menghapus", Toast.LENGTH_SHORT).show()
                }
            },
        )
        return
    }
    if (composing) {
        BackHandler { composing = false }
        NewThreadScreen(
            onClose = { composing = false },
            onPost = { title, body, cat, tags ->
                scope.launch {
                    val r = TnData.forumApi()?.createThread(installId, CURRENT_USER, title, body, cat, tags.map { it.label }, level, xpInto)
                    when {
                        r == null -> Toast.makeText(ctx, "Panel tidak tersedia", Toast.LENGTH_SHORT).show()
                        r.success -> {
                            Toast.makeText(ctx, r.message ?: "Thread diposting", Toast.LENGTH_SHORT).show()
                            TnData.refreshUserXp()
                            composing = false; category = "all"; sort = "new"; reload()
                        }
                        else -> Toast.makeText(ctx, r.message ?: "Gagal posting", Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
        return
    }

    val pinned = threads.filter { it.pinned }
    val ordered = pinned + threads.filter { !it.pinned } // server already sorts; pinned float to top

    Box(Modifier.fillMaxWidth()) {
        PageScroll {
            ForumHero(threadCount = threads.size, commentCount = threads.sumOf { it.replies })
            Spacer(Modifier.height(12.dp))
            Segmented(
                options = listOf("hot" to "Hot", "new" to "Terbaru", "top" to "Top"),
                selected = sort,
                icons = mapOf("hot" to "fire", "new" to "sparkle", "top" to "trophy"),
                onSelect = { sort = it },
            )
            Spacer(Modifier.height(12.dp))
            LazyRow(
                modifier = Modifier.bleedEnd(20.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(FORUM_CATS.size) { i ->
                    val cat = FORUM_CATS[i]
                    TnChip(text = cat.label, selected = category == cat.id, leadingIcon = cat.icon, onClick = { category = cat.id })
                }
            }
            Spacer(Modifier.height(12.dp))
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TnTheme.colors.rose)
                }
                ordered.isEmpty() -> ForumEmpty()
                else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ordered.forEach { t ->
                        ThreadCard(t, voted = voted.value.contains(t.id), onVote = { doVote(t) }, onOpen = {
                            scope.launch {
                                openThread = TnData.forumApi()?.getThread(t.id, installId)?.let { (ft, posts) -> ft.toModel(posts) } ?: t
                            }
                        })
                    }
                }
            }
            Spacer(Modifier.height(90.dp))
        }
        TnPrimaryButton(
            text = "Thread Baru",
            icon = "plus",
            onClick = { composing = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }
}

private fun Set<Int>.toggle(id: Int) = if (contains(id)) this - id else this + id

/** Map a panel thread (+ optional loaded posts) to the UI model. */
private fun FThread.toModel(posts: List<FPost> = emptyList()) = ForumThread(
    id = id, pinned = isPinned, cat = category, title = title,
    excerpt = content ?: "", user = authorName.ifBlank { "anon" },
    role = null, time = relTime(createdAt), votes = upvotes - downvotes,
    replies = replyCount, views = "", tags = tags.map { TagChip(it) }, art = 0, thumb = false,
    comments = posts.map { it.toReply() }, realm = authorRealm.ifBlank { null },
)

private fun FPost.toReply() = ForumReply(
    id = id, user = authorName.ifBlank { "anon" }, role = null,
    time = relTime(createdAt), votes = 0, text = content, nested = parentPostId != null,
    realm = authorRealm.ifBlank { null },
)

/** Relative time from a UTC "yyyy-MM-dd HH:mm:ss" (or ISO) timestamp. */
private fun relTime(raw: String): String {
    if (raw.isBlank()) return ""
    val ms = runCatching {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        fmt.parse(raw.replace('T', ' ').take(19))?.time
    }.getOrNull() ?: return ""
    val diff = (System.currentTimeMillis() - ms).coerceAtLeast(0L)
    val min = diff / 60000
    return when {
        min < 1 -> "Baru saja"
        min < 60 -> "$min menit lalu"
        min < 1440 -> "${min / 60} jam lalu"
        else -> "${min / 1440} hari lalu"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewThreadScreen(
    onClose: () -> Unit,
    onPost: (title: String, body: String, cat: String, tags: List<TagChip>) -> Unit,
) {
    val c = TnTheme.colors
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("umum") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    val tagPresets = remember {
        listOf(
            TagChip("Spoiler", "spoiler"), TagChip("Donghua"), TagChip("Rekomendasi"),
            TagChip("Cultivation"), TagChip("Fan Art", "grape"), TagChip("Bantuan", "coral"), TagChip("Diskusi"),
        )
    }
    val cats = remember { FORUM_CATS.filter { it.id != "all" } }

    PageScroll {
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.clip(RoundedCornerShape(TnRadii.pill)).clickable { onClose() }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TnIcon("chevR", size = 17.dp, tint = c.ink2, modifier = Modifier.rotate180())
            Text("Batal", color = c.ink2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text("Buat Thread Baru", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
        Spacer(Modifier.height(16.dp))

        FormField("Judul", title, "", singleLine = true) { title = it }
        Spacer(Modifier.height(14.dp))
        FormField("Isi", body, "", singleLine = false, minHeight = 140.dp) { body = it }
        Spacer(Modifier.height(16.dp))

        Text("Kategori", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        LazyRow(modifier = Modifier.bleedEnd(20.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            items(cats.size) { i ->
                val cc = cats[i]
                TnChip(text = cc.label, selected = cat == cc.id, leadingIcon = cc.icon, onClick = { cat = cc.id })
            }
        }
        Spacer(Modifier.height(16.dp))

        Text("Tag (maks 3)", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tagPresets.forEach { tg ->
                val on = tg.label in picked
                TagToggle(tg, on) {
                    picked = when {
                        on -> picked - tg.label
                        picked.size >= 3 -> picked
                        else -> picked + tg.label
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        TnPrimaryButton(
            text = "Posting Thread",
            icon = "send",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (title.isBlank()) {
                    Toast.makeText(context, "Judul wajib diisi", Toast.LENGTH_SHORT).show()
                } else {
                    onPost(title.trim(), body.trim(), cat, tagPresets.filter { it.label in picked })
                }
            },
        )
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    placeholder: String,
    singleLine: Boolean,
    minHeight: Dp = 0.dp,
    onValueChange: (String) -> Unit,
) {
    val c = TnTheme.colors
    Text(label, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Spacer(Modifier.height(8.dp))
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(TnRadii.md)).background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(TnRadii.md))
            .heightIn(min = minHeight).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, color = c.muted, fontSize = 15.sp)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = TextStyle(color = c.ink, fontSize = 15.sp),
            cursorBrush = SolidColor(c.rose),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TagToggle(tg: TagChip, on: Boolean, onClick: () -> Unit) {
    val c = TnTheme.colors
    val (bg, fg) = when (tg.color) {
        "grape" -> c.grapeSoft to c.grape
        "spoiler" -> c.ink to Color.White
        "coral" -> c.coralSoft to c.coral
        else -> c.roseTint to c.roseDeep
    }
    Row(
        Modifier.clip(RoundedCornerShape(TnRadii.pill))
            .background(if (on) bg else c.surface)
            .border(1.dp, if (on) fg else c.line, RoundedCornerShape(TnRadii.pill))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (tg.color == "spoiler" && on) TnIcon("eye", size = 12.dp, tint = fg)
        Text(tg.label, color = if (on) fg else c.ink2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ForumHero(threadCount: Int, commentCount: Int) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TnRadii.lg))
            .tnGradient(HeroGradientColors)
            .padding(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Komunitas TetoNova", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
            Text(
                "Diskusi donghua, rekomendasi, fan art, sampai bantuan teknis — semua di satu tempat. Spoiler-tag aktif, moderasi ramah.",
                color = Color.White.copy(0.85f), fontSize = 13.sp,
            )
            // Real counts from your own posts/comments — no fabricated member/online numbers.
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                HeroStat(threadCount.toString(), "Thread")
                HeroStat(commentCount.toString(), "Komentar")
            }
        }
    }
}

@Composable
private fun HeroStat(value: String, label: String) {
    Column {
        Text(value, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
        Text(label, color = Color.White.copy(0.8f), fontSize = 11.sp)
    }
}

@Composable
private fun ForumEmpty() {
    val c = TnTheme.colors
    TnCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TnIcon("comment", size = 30.dp, tint = c.muted)
            Text("Belum ada thread", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Mulai diskusi pertamamu lewat tombol Thread Baru.", color = c.muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Segmented(
    options: List<Pair<String, String>>,
    selected: String,
    icons: Map<String, String>,
    onSelect: (String) -> Unit,
) {
    val c = TnTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(TnRadii.pill))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(TnRadii.pill))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (id, label) ->
            val on = id == selected
            Row(
                Modifier
                    .clip(RoundedCornerShape(TnRadii.pill))
                    .background(if (on) c.rose else Color.Transparent)
                    .clickable { onSelect(id) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                icons[id]?.let { TnIcon(it, size = 15.dp, tint = if (on) Color.White else c.muted, filled = on && id == "hot") }
                Text(label, color = if (on) Color.White else c.muted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChips(tags: List<TagChip>, modifier: Modifier = Modifier) {
    val c = TnTheme.colors
    // FlowRow so many tags wrap to the next line instead of squeezing the last one to a sliver.
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { tg ->
            val (bg, fg) = when (tg.color) {
                "grape" -> c.grapeSoft to c.grape
                "spoiler" -> c.ink to Color.White
                "coral" -> c.coralSoft to c.coral
                else -> c.roseTint to c.roseDeep
            }
            Row(
                Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (tg.color == "spoiler") TnIcon("eye", size = 12.dp, tint = fg)
                Text(tg.label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ThreadCard(t: ForumThread, voted: Boolean, onVote: () -> Unit, onOpen: () -> Unit) {
    val c = TnTheme.colors
    TnCard(Modifier.fillMaxWidth().clickable { onOpen() }) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VoteBox(count = t.votes, voted = voted, onToggle = onVote)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (t.pinned) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            TnIcon("pin", size = 13.dp, tint = c.rose)
                            Text("Pinned", color = c.rose, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Avatar(t.user, 26.dp)
                    Text("@${t.user}", color = c.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    RoleBadge(t.role)
                    Text("· ${t.time}", color = c.muted, fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(t.title, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text(t.excerpt, color = c.muted, fontSize = 13.sp, maxLines = 2)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // At most 2 tags, weighted so they take only the leftover width (wrapping if
                    // needed) and can never push the reply/view counts off the card's right edge.
                    TagChips(t.tags.take(2), Modifier.weight(1f))
                    FootStat("comment", t.comments.size.toString())
                    Spacer(Modifier.width(12.dp))
                    FootStat("eye", t.views)
                }
            }
        }
    }
}

@Composable
private fun FootStat(icon: String, value: String) {
    val c = TnTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TnIcon(icon, size = 15.dp, tint = c.muted)
        Text(value, color = c.muted, fontSize = 12.sp, maxLines = 1, softWrap = false)
    }
}

/** Small cultivation-realm marker shown beside a forum author's name. */
@Composable
private fun RealmChip(realm: String?) {
    if (realm.isNullOrBlank()) return
    val c = TnTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(c.roseTint).padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        TnIcon("sparkle", size = 10.dp, tint = c.roseDeep, filled = true)
        Text(realm, color = c.roseDeep, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
fun RoleBadge(role: String?) {
    val c = TnTheme.colors
    when (role) {
        "mod" -> Row(
            Modifier.clip(RoundedCornerShape(TnRadii.pill)).tnGradient(HeroGradientColors).padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            TnIcon("shield", size = 11.dp, tint = Color.White)
            Text("Mod", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
        }
        "op" -> Pill("OP", bg = c.roseSoft, fg = c.roseDeep)
    }
}

@Composable
private fun VoteBox(count: Int, voted: Boolean, onToggle: () -> Unit) {
    val c = TnTheme.colors
    Column(
        Modifier
            .clip(RoundedCornerShape(TnRadii.sm))
            .background(if (voted) c.roseSoft else c.surface2)
            .border(1.dp, c.line, RoundedCornerShape(TnRadii.sm))
            .clickable { onToggle() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TnIcon("arrowUp", size = 18.dp, tint = if (voted) c.rose else c.muted)
        Text((if (voted) count + 1 else count).toString(), color = if (voted) c.rose else c.ink2, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThreadDetail(
    thread: ForumThread, voted: Boolean, onVote: () -> Unit, onBack: () -> Unit, onReply: (String) -> Unit,
    onDelete: () -> Unit, onDeleteComment: (Int) -> Unit,
) {
    val c = TnTheme.colors
    var liked by remember { mutableStateOf(false) }
    var reply by remember(thread.id) { mutableStateOf("") }
    var confirmDelete by remember(thread.id) { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Hapus thread?") },
            text = { Text("Thread ini beserta semua komentarnya akan dihapus permanen.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Hapus", color = c.rose, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Batal", color = c.ink2) } },
        )
    }
    PageScroll {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(TnRadii.pill)).clickable { onBack() }.padding(vertical = 6.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TnIcon("chevR", size = 17.dp, tint = c.ink2, modifier = Modifier.rotate180())
                Text("Kembali ke forum", color = c.ink2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(Modifier.weight(1f))
            // Only the author sees delete — other users can never remove someone else's thread.
            if (thread.user == CURRENT_USER) {
                Box(Modifier.clip(RoundedCornerShape(TnRadii.pill)).clickable { confirmDelete = true }.padding(8.dp)) {
                    TnIcon("trash", size = 18.dp, tint = c.muted)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        TnCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Avatar(thread.user, 32.dp)
                    Text("@${thread.user}", color = c.ink2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    RoleBadge(thread.role)
                    Text("· ${thread.time}", color = c.muted, fontSize = 11.sp)
                }
                if (!thread.realm.isNullOrBlank()) { Spacer(Modifier.height(8.dp)); RealmChip(thread.realm) }
                Spacer(Modifier.height(12.dp))
                Text(thread.title, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                if (thread.tags.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    TagChips(thread.tags)
                }
                // Body is exactly what the author wrote — no synthetic filler appended.
                if (thread.excerpt.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text(thread.excerpt, color = c.ink2, fontSize = 14.sp, lineHeight = 21.sp)
                }
                Spacer(Modifier.height(16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReactPill("arrowUp", "Upvote ${if (voted) thread.votes + 1 else thread.votes}", on = voted, onClick = onVote)
                    ReactPill("heart", "Suka", on = liked, filled = liked, onClick = { liked = !liked })
                    ReactPill("bookmark", "Simpan", on = false, onClick = {})
                }
            }
        }
        SectionHead(title = "Komentar", sub = "${thread.comments.size} balasan")
        // Real comment composer — appends a comment to this thread and persists it.
        TnCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(TnRadii.md)).background(c.surface2)
                        .border(1.dp, c.line, RoundedCornerShape(TnRadii.md)).padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    if (reply.isEmpty()) Text("Tulis komentar…", color = c.muted, fontSize = 14.sp)
                    BasicTextField(
                        value = reply, onValueChange = { reply = it },
                        textStyle = TextStyle(color = c.ink, fontSize = 14.sp),
                        cursorBrush = SolidColor(c.rose), modifier = Modifier.fillMaxWidth(),
                    )
                }
                val canSend = reply.isNotBlank()
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(TnRadii.pill))
                        .background(if (canSend) c.rose else c.surface2)
                        .clickable(enabled = canSend) { onReply(reply.trim()); reply = "" },
                    contentAlignment = Alignment.Center,
                ) {
                    TnIcon("send", size = 18.dp, tint = if (canSend) Color.White else c.muted)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (thread.comments.isEmpty()) {
            TnCard(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("Belum ada komentar. Jadilah yang pertama!", color = c.muted, fontSize = 13.sp)
                }
            }
        } else {
            TnCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    thread.comments.forEach { r ->
                        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Avatar(r.user, 40.dp)
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("@${r.user}", color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    RoleBadge(r.role)
                                    Text("· ${r.time}", color = c.muted, fontSize = 11.sp)
                                }
                                if (!r.realm.isNullOrBlank()) { Spacer(Modifier.height(3.dp)); RealmChip(r.realm) }
                                Spacer(Modifier.height(4.dp))
                                Text(r.text, color = c.ink2, fontSize = 13.5.sp)
                            }
                            // Only the comment's author can delete it.
                            if (r.user == CURRENT_USER) {
                                Box(Modifier.clip(RoundedCornerShape(TnRadii.pill)).clickable { onDeleteComment(r.id) }.padding(6.dp)) {
                                    TnIcon("trash", size = 15.dp, tint = c.muted)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ReactPill(icon: String, label: String, on: Boolean, filled: Boolean = false, onClick: () -> Unit) {
    val c = TnTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(TnRadii.pill))
            .background(if (on) c.roseSoft else c.surface2)
            .border(1.dp, if (on) c.rose else c.line, RoundedCornerShape(TnRadii.pill))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TnIcon(icon, size = 16.dp, tint = if (on) c.rose else c.ink2, filled = filled)
        Text(label, color = if (on) c.rose else c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

private fun Modifier.rotate180(): Modifier = this.then(Modifier.graphicsLayer(rotationZ = 180f))
