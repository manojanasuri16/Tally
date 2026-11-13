package com.tally.book.ui.loans

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.tally.book.core.*
import com.tally.book.data.Ledger
import com.tally.book.model.*
import com.tally.book.pendingSharedText
import com.tally.book.ui.AppNav
import com.tally.book.ui.Routes
import com.tally.book.ui.ScreenPadding
import com.tally.book.ui.SubScreen
import com.tally.book.ui.components.*
import com.tally.book.ui.theme.Apple
import com.tally.book.ui.theme.LocalSemantic
import com.tally.book.ui.theme.Motion
import com.tally.book.ui.theme.EasyMode
import com.tally.book.ui.theme.LocalEasy
import com.tally.book.ui.theme.bigText
import com.tally.book.ui.theme.stackGap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/* The root screen and the routes of the loan screens. */

const val ROUTE_LOAN = "loan/{id}"
/** The Family page (members, invite, join, language, lock); registered by familyGraph() in ui/family/FamilyScreen.kt. */
const val ROUTE_FAMILY = "family"

fun openLoan(id: String) = AppNav.go("loan/$id")

/** Registers the screens: the root (Welcome until the phone has a name, then LoansScreen), a loan, and the Family page. */
fun NavGraphBuilder.loansGraph() {
    composable(Routes.LOANS) { RootScreen() }
    composable(ROUTE_LOAN, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
        LoanDetailScreen(e.arguments?.getString("id").orEmpty())
    }
}

@Composable
private fun RootScreen() {
    val rev = rememberLedger()
    val named = remember(rev) { DB.o("me")?.s("name").orEmpty().isNotBlank() }
    Crossfade(named, animationSpec = tween(220), label = "root") { if (it) LoansScreen() else WelcomeScreen() }
}

// ---------- welcome ----------

/** First run: one question (the name). Easy mode is only turned on from its switch on the Family page. */
@Composable
fun WelcomeScreen() {
    var name by remember { mutableStateOf("") }
    val haptics = rememberHaptics()
    val question = t("welcome.name", "What is your name?")
    val restore = restorePicker?.invoke()
    fun finish() {
        haptics.confirm()
        Ledger.edit { setMyName(name) }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(
            Modifier.align(Alignment.Center).widthIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(question, style = Apple.type.title1, color = Apple.colors.label, modifier = Modifier.semantics { contentDescription = question })
            TextInput(name, { name = it }, "", placeholder = t("welcome.placeholder", "Your name"))
            PrimaryButton(t("welcome.continue", "Continue"), { finish() }, Modifier.fillMaxWidth(), enabled = name.isNotBlank())
            // a new phone: bring the book back instead of starting again
            if (restore != null) PlainButton(t("welcome.haveBackup", "I have a backup"), restore, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

// ---------- home ----------

private class Home(
    val totals: Obj, val due: List<Obj>, val expected: List<Obj>, val groups: List<Pair<String, List<Obj>>>, val given: List<Obj>,
    val closed: List<Obj>, val hasGiven: Boolean, val priv: PrivateLoans, val any: Boolean, val owed: Map<String, Double>, val hasTaken: Boolean, val showAdder: Boolean,
)

/** A pending Pay / Received tap: the loan and what the row was for ("principal" starts the form as a part repayment). */
private class PayFor(val loanId: String, val kind: String?)

private fun readHome(): Home {
    val all = loans()
    val open = all.filter { !it.b("closed") }
    val owed = all.associate { it.s("id") to loanView(it).num("owed") }
    val (shared, priv) = splitPrivate(open, owed)
    val taken = open.filter { !isGiven(it) }
    val kinds = LOAN_KINDS + taken.map { it.s("kind") }.filter { it !in LOAN_KINDS }.distinct()
    val groups = kinds.map { k -> k to shared.filter { !isGiven(it) && it.s("kind") == k }.sortedBy { loanTitle(it).lowercase() } }.filter { it.second.isNotEmpty() }
    val given = open.filter { isGiven(it) }.sortedBy { loanTitle(it).lowercase() }
    val givenShared = shared.filter { isGiven(it) }.sortedBy { loanTitle(it).lowercase() }
    val closed = all.filter { it.b("closed") }.sortedByDescending { it.s("closedDate") }
    val rows = dueList()
    return Home(totals(), rows.filter { !it.b("given") }, rows.filter { it.b("given") }, groups, givenShared, closed, open.any { isGiven(it) }, priv, all.isNotEmpty(), owed, taken.isNotEmpty(), bookHasOthers())
}

@Composable
fun LoansScreen() {
    val rev = rememberLedger()
    val home = remember(rev) { readHome() }
    var payFor by remember { mutableStateOf<PayFor?>(null) }
    var adding by remember { mutableStateOf(false) }
    val offerReminders = com.tally.book.ui.family.rememberReminderOffer()
    var closedOpen by rememberSaveable { mutableStateOf(false) }

    val pull = rememberPullRefresh {
        val hook = syncHook
        if (hook == null) Dialogs.snack(t("home.syncOff", "Sync is not set up yet"))
        else try { hook() } catch (e: CancellationException) { throw e } catch (e: Exception) { Dialogs.snack(t("home.syncFailed", "Could not sync. Please try again.")) }
    }

    val easy = LocalEasy.current

    // The pull's connection sits above NavScreen, so the large title takes a downward drag first and the pull starts only once the title is fully open.
    Box(Modifier.fillMaxSize().nestedScroll(pull.connection)) {
        NavScreen(
            t("app.name", "Tally"), back = null, largeTitle = true,
            actions = { BarIcon(Icons.Rounded.Group, t("home.family", "Family"), { AppNav.go(ROUTE_FAMILY) }) },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                PullRefreshIndicator(pull)
                Column(
                    Modifier.fillMaxSize().pullRefreshContent(pull).verticalScroll(rememberScrollState()).padding(ScreenPadding),
                    verticalArrangement = Arrangement.spacedBy(stackGap()),
                ) {
                    joinRequestsCard?.invoke()
                    if (!home.any) {
                        EmptyState(t("home.empty", "No entries yet. Add one to start keeping track."), icon = Icons.Rounded.Group)
                        syncStatusLine?.let { line -> Box(Modifier.padding(start = 4.dp)) { line() } }
                        backupLine?.invoke()
                    } else {
                        HomeHero(home)
                        if (home.hasTaken) DueSection(home.due, onPay = { id, k -> payFor = PayFor(id, k) }, onOpen = { openLoan(it) })
                        if (home.hasGiven && home.expected.isNotEmpty()) DueSection(home.expected, onPay = { id, k -> payFor = PayFor(id, k) }, onOpen = { openLoan(it) }, given = true)
                        // Easy mode leaves out "added by" under each name: one line per loan
                        home.groups.forEach { (kind, list) -> LoanGroup(kindLabel(kind), list, home.owed, home.showAdder && !easy) }
                        if (home.given.isNotEmpty()) LoanGroup(t("home.gave", "Money I gave"), home.given, home.owed, home.showAdder && !easy)
                        if (home.priv.loans.isNotEmpty()) PrivateGroup(home.priv, home.owed)
                        if (home.closed.isNotEmpty()) ClosedGroup(home.closed, closedOpen) { closedOpen = !closedOpen }
                    }
                    PrimaryButton(t("home.addLoan", "Add an entry"), { adding = true }, Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        }
    }

    payFor?.let { PaymentSheet(it.loanId, null, startKind = it.kind, onDismiss = { payFor = null }) }
    if (adding) LoanSheet(null, onDismiss = { adding = false }, onSaved = { adding = false; offerReminders() })
    ShareIntake()
}

@Composable
private fun HomeHero(home: Home) {
    val toReceive = home.totals.num("toReceive")
    val hasGiven = home.hasGiven
    if (home.hasTaken || !hasGiven) {
        Hero(
            t("home.stillToPay", "Still to pay"), home.totals.num("owed"),
            sub = t("home.paidThisMonth", "Paid this month: {amt}", "amt" to amtText(home.totals.num("paidThisMonth"))),
        )
        if (hasGiven) AGroup { item { ARow(loanWords(true).toReturn, money = toReceive, moneyColor = Apple.colors.label) } }
    } else {
        /* only money I gave: the lead figure is what is to come back, not a "still to pay" of nothing */
        Hero(
            loanWords(true).left, toReceive,
            sub = t("home.receivedThisMonth", "Received this month: {amt}", "amt" to amtText(home.totals.num("receivedThisMonth"))),
        )
    }
    val line = syncStatusLine
    if (line != null) Box(Modifier.padding(start = 4.dp)) { line() }
    backupLine?.invoke()
    com.tally.book.ui.family.BackupReminderCard()
}

@Composable
private fun DueSection(due: List<Obj>, onPay: (String, String?) -> Unit, onOpen: (String) -> Unit, given: Boolean = false) {
    val w = loanWords(given)
    // the month's total beside the heading: everything listed, pending amounts included (each row's figure already includes them)
    val total = due.sumOf { it.num("amount") }
    AGroup(
        title = w.expected,
        trailingHead = if (due.isEmpty()) null else ({ Money(total, style = Apple.type.subheadline, fontWeight = FontWeight.SemiBold, color = Apple.colors.label) }),
    ) {
        if (due.isEmpty()) item { NoteRow(if (given) t("home.nothingExpected", "Nothing expected this month") else t("home.nothingDue", "Nothing due this month")) }
        else due.forEach { d ->
            val loan = d.o("loan")!!
            val principal = d.s("kind") == "principal"
            item {
                DueRow(
                    loan, d.s("date"), d.num("amount"), d.b("overdue"), if (principal) w.toReturn else null, given,
                    note = dueRowNote(loan, d.num("pending"), d.num("count").toInt(), amtText(d.num("pending"))),
                    onPay = { onPay(loan.s("id"), if (principal) "principal" else null) }, onOpen = { onOpen(loan.s("id")) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DueRow(loan: Obj, date: String, amount: Double, overdue: Boolean, tag: String?, given: Boolean, note: String?, onPay: () -> Unit, onOpen: () -> Unit) {
    val s = LocalSemantic.current
    val easy = LocalEasy.current
    val stacked = bigText()
    val label = if (given) t("home.received", "Received") else t("home.pay", "Pay")
    val details: @Composable (Modifier) -> Unit = { m ->
        Column(m.pressable(onClick = onOpen).padding(vertical = 4.dp)) {
            Text(loanTitle(loan), style = Apple.type.body.copy(fontWeight = FontWeight.SemiBold), color = Apple.colors.label)
            loanSecond(loan).takeIf { it.isNotEmpty() }?.let { Text(it, style = Apple.type.subheadline, color = Apple.colors.secondaryLabel) }
            // an overdue payment is a pill and a red date; the date wraps under the pill when there is no room beside it
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                if (overdue) Pill(t("home.overdue", "Overdue"), s.negative, Modifier.align(Alignment.CenterVertically))
                val when_ = dateInWords(date)
                Text(if (tag != null) "$tag · $when_" else when_, style = Apple.type.subheadline, color = if (overdue) s.negative else Apple.colors.secondaryLabel)
            }
            // the amount is the big figure of the row
            Money(amount, style = if (easy) Apple.type.title2 else Apple.type.title3, color = Apple.colors.label)
            if (note != null) Text(note, style = Apple.type.footnote, color = Apple.colors.secondaryLabel)
        }
    }
    if (stacked) {
        // large text or Easy mode: the details, then a full-width Pay button under them
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            details(Modifier)
            SecondaryButton(label, onPay, Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            details(Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            SecondaryButton(label, onPay, Modifier.heightIn(min = 48.dp).widthIn(min = 72.dp))
        }
    }
}

@Composable
private fun LoanGroup(title: String, list: List<Obj>, owed: Map<String, Double>, showAdder: Boolean) {
    AGroup(title = title) {
        list.forEach { l ->
            item {
                ARow(
                    loanTitle(l), sub = loanRowSub(loanSecond(l), if (showAdder) memberName(l.s("by")) else null), money = owed[l.s("id")] ?: 0.0, moneyColor = Apple.colors.label,
                    chevron = true, onClick = { openLoan(l.s("id")) },
                    trailing = if (l.b("private")) ({ PrivateMark() }) else null,
                )
            }
        }
    }
}

/** Open private loans together: taken and given, one header total per direction, a line saying they stay on this phone. */
@Composable
private fun PrivateGroup(p: PrivateLoans, owed: Map<String, Double>) {
    val total = listOfNotNull(
        if (p.hasTaken) "${t("home.toPay", "To pay")} ${amtText(p.toPay)}" else null,
        if (p.hasGiven) "${loanWords(true).toReturn} ${amtText(p.toComeBack)}" else null,
    ).joinToString(" · ")
    AGroup(
        title = t("home.private", "Only on this phone"), footer = t("home.private.foot", "Your family doesn't see these."),
        // Easy mode drops the totals in the header (the figure above already says it)
        trailingHead = if (LocalEasy.current) null else ({ Text(total, style = Apple.type.subheadline, maxLines = 1) }),
    ) {
        p.loans.forEach { l ->
            item {
                ARow(
                    loanTitle(l), sub = listOf(loanKindLabel(l), loanSecond(l)).filter { it.isNotBlank() }.joinToString(" · "),
                    money = owed[l.s("id")] ?: 0.0, moneyColor = Apple.colors.label, chevron = true, onClick = { openLoan(l.s("id")) },
                    trailing = { PrivateMark() },
                )
            }
        }
    }
}

/** The small lock on a private loan. */
@Composable
fun PrivateMark() {
    Icon(Icons.Rounded.Lock, t("loan.private", "Private"), Modifier.size(16.dp), tint = Apple.colors.tertiaryLabel)
}

@Composable
private fun ClosedGroup(list: List<Obj>, open: Boolean, toggle: () -> Unit) {
    val calm = reduceMotion()
    val angle by animateFloatAsState(if (open) 90f else 0f, if (calm) Motion.fade() else Motion.control(), label = "closed")
    AGroup {
        item {
            ARow(
                t("home.closed", "Closed entries"), value = list.size.toString(), onClick = toggle,
                trailing = { Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, null, Modifier.size(14.dp).rotate(angle), tint = Apple.colors.tertiaryLabel) },
            )
        }
    }
    AnimatedVisibility(
        open,
        enter = if (calm) fadeIn(Motion.fade()) else expandVertically(Motion.standard()) + fadeIn(Motion.standard()),
        exit = if (calm) fadeOut(Motion.fade()) else shrinkVertically(Motion.standard()) + fadeOut(Motion.standard()),
    ) {
        Column {
            AGroup {
                list.forEach { l ->
                    item {
                        ARow(
                            loanTitle(l), sub = listOf(loanKindLabel(l), loanSecond(l)).filter { it.isNotBlank() }.joinToString(" · "),
                            value = fmtISODate(l.s("closedDate")).ifEmpty { null }, chevron = true, onClick = { openLoan(l.s("id")) },
                            trailing = if (l.b("private")) ({ PrivateMark() }) else null,
                        )
                    }
                }
            }
        }
    }
}

// ---------- sharing a bank message to Tally ----------

/**
 * A text shared to the app (pendingSharedText) is taken here once the app is unlocked and named: the person picks the loan
 * (the only open loan is chosen for them) and Log payment opens with the text in the bank-message field. Checked when the
 * screen appears and every time the app comes back to the front (a share while it was already open).
 */
@Composable
fun ShareIntake() {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var loanId by remember { mutableStateOf<String?>(null) }
    var choices by remember { mutableStateOf<List<Obj>>(emptyList()) }

    fun take() {
        val shared = pendingSharedText ?: return
        pendingSharedText = null
        val open = loans().filter { !it.b("closed") }
        when {
            open.isEmpty() -> scope.launch { Dialogs.tell(t("share.noLoan", "Add an entry first, then share the message again.")) }
            open.size == 1 -> { text = shared; loanId = open[0].s("id") }
            else -> { text = shared; choices = open.sortedBy { loanTitle(it).lowercase() } }
        }
    }
    LaunchedEffect(Unit) { take() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { take() }

    val shared = text
    if (shared != null && loanId == null) {
        EditSheet(t("share.which", "Which entry is this payment for?"), onDismiss = { text = null }, onSave = null) {
            InsetSurface {
                choices.forEachIndexed { i, l ->
                    if (i > 0) Hairline(start = 16.dp)
                    ARow(loanTitle(l), sub = listOf(loanKindLabel(l), loanSecond(l)).filter { it.isNotBlank() }.joinToString(" · "), chevron = true, onClick = { loanId = l.s("id") })
                }
            }
        }
    }
    val id = loanId
    if (shared != null && id != null) PaymentSheet(id, null, sms = shared, onDismiss = { text = null; loanId = null })
}
