package fr.bellenguez.lifeos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * PLAN — calendrier et planning fusionnés.
 * Grille du mois → jour sélectionné → blocs du jour (éditables) + événements.
 * Les plans types (LUN–VEN / SAM / DIM) restent éditables en bas.
 */

private val FR_DAYS = listOf("L", "M", "M", "J", "V", "S", "D")

private fun keyOf(cal: Calendar): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)

private fun frDate(key: String): String = try {
    val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(key)!!
    SimpleDateFormat("EEE d MMM", Locale.FRANCE).format(d).uppercase()
} catch (e: Exception) { key }

private fun dayNameOf(key: String): String = try {
    val cal = Calendar.getInstance().apply {
        time = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(key)!!
    }
    when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.SUNDAY -> Schedule.DAY_SUNDAY
        Calendar.SATURDAY -> Schedule.DAY_SATURDAY
        else -> Schedule.DAY_WEEKDAY
    }
} catch (e: Exception) { Schedule.DAY_WEEKDAY }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlanCalScreen(onChange: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    var monthCal by remember {
        mutableStateOf(Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) })
    }
    var selected by remember { mutableStateOf(Store.dateKey(0)) }
    var newTime by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var newNn by remember { mutableStateOf(false) }
    var newRec by remember { mutableStateOf("none") }
    var editingEvent by remember { mutableStateOf<Long?>(null) } // appui long → édition

    val todayKey = Store.dateKey(0)
    val events = remember(version) { Store.events() }
    val recLabels = mapOf("none" to "UNIQUE", "daily" to "JOUR", "weekly" to "SEM.", "monthly" to "MOIS")

    fun bump() { version++; onChange() }

    fun resetForm() {
        newTime = ""; newName = ""; newNn = false; newRec = "none"; editingEvent = null
    }


    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Label("Plan — calendrier et journées", modifier = Modifier.padding(vertical = 16.dp))

            /* ----- Grille du mois ----- */
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("‹", color = Bw.G4, fontSize = 24.sp,
                    modifier = Modifier
                        .clickable { monthCal = (monthCal.clone() as Calendar).apply { add(Calendar.MONTH, -1) } }
                        .padding(horizontal = 14.dp))
                Text(
                    SimpleDateFormat("MMMM yyyy", Locale.FRANCE).format(monthCal.time).uppercase(),
                    color = Bw.White, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp
                )
                Text("›", color = Bw.G4, fontSize = 24.sp,
                    modifier = Modifier
                        .clickable { monthCal = (monthCal.clone() as Calendar).apply { add(Calendar.MONTH, 1) } }
                        .padding(horizontal = 14.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                FR_DAYS.forEach {
                    Text(it, color = Bw.G4, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            val firstOffset = ((monthCal.get(Calendar.DAY_OF_WEEK) + 5) % 7)
            val daysInMonth = monthCal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val rows = (firstOffset + daysInMonth + 6) / 7
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (c in 0 until 7) {
                        val dayNum = r * 7 + c - firstOffset + 1
                        if (dayNum in 1..daysInMonth) {
                            val dCal = (monthCal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, dayNum) }
                            val key = keyOf(dCal)
                            val isSel = key == selected
                            val isToday = key == todayKey
                            val busy = events.any { Schedule.eventOccursOn(it, key) }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1.1f)
                                    .padding(2.dp)
                                    .then(
                                        when {
                                            isSel -> Modifier.background(Bw.White)
                                            isToday -> Modifier.border(1.dp, Bw.White)
                                            else -> Modifier
                                        }
                                    )
                                    .clickable { selected = key; resetForm() },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "$dayNum",
                                        color = if (isSel) Bw.Black else Bw.White,
                                        fontSize = 13.sp,
                                        fontWeight = if (isToday || isSel) FontWeight.Black else FontWeight.Normal
                                    )
                                    if (busy) Text("•", color = if (isSel) Bw.Black else Bw.G5, fontSize = 9.sp, lineHeight = 9.sp)
                                }
                            }
                        } else Spacer(Modifier.weight(1f).aspectRatio(1.1f))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        /* ----- Événements du jour sélectionné ----- */
        run {
            item {
                Spacer(Modifier.height(14.dp))
                Label("Événements du ${frDate(selected)} — appui long pour éditer", color = Bw.White)
                Spacer(Modifier.height(4.dp))
            }
            val dayEvents = events
                .filter { Schedule.eventOccursOn(it, selected) }
                .sortedBy { it.optString("t") }
            items(dayEvents) { e ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                editingEvent = e.optLong("id")
                                newTime = e.optString("t")
                                newName = e.optString("n")
                                newNn = e.optBoolean("nn")
                                newRec = e.optString("rec", "none")
                            }
                        )
                        .then(
                            if (editingEvent == e.optLong("id")) Modifier.border(1.dp, Bw.White).padding(6.dp)
                            else Modifier
                        )
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(e.optString("t"), color = Bw.G4, fontSize = 11.sp, modifier = Modifier.width(54.dp))
                    Text(
                        e.optString("n").uppercase(),
                        color = Bw.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (e.optString("rec", "none") != "none") Text("↻ ", color = Bw.G4, fontSize = 12.sp)
                    if (e.optBoolean("nn")) {
                        Text("NN", color = Bw.White, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.border(1.dp, Bw.White).padding(horizontal = 3.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        "✕", color = Bw.G4, fontSize = 13.sp,
                        modifier = Modifier
                            .border(1.dp, Bw.G3)
                            .clickable { Store.removeEvent(e.optLong("id")); bump() }
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }
            item {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTime, onValueChange = { newTime = it },
                        label = { Text("HEURE", fontSize = 9.sp, letterSpacing = 2.sp) },
                        modifier = Modifier.width(88.dp), colors = bwFieldColors(),
                        textStyle = TextStyle(fontSize = 13.sp)
                    )
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it },
                        label = { Text("ÉVÉNEMENT", fontSize = 9.sp, letterSpacing = 2.sp) },
                        modifier = Modifier.weight(1f), colors = bwFieldColors(),
                        textStyle = TextStyle(fontSize = 13.sp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "NN", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                        color = if (newNn) Bw.Black else Bw.G4,
                        modifier = Modifier
                            .then(if (newNn) Modifier.background(Bw.White) else Modifier.border(1.dp, Bw.G3))
                            .clickable { newNn = !newNn }
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                    )
                    Text(
                        "↻ ${recLabels[newRec]}", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                        color = if (newRec != "none") Bw.White else Bw.G4,
                        modifier = Modifier
                            .border(1.dp, if (newRec != "none") Bw.White else Bw.G3)
                            .clickable {
                                val order = listOf("none", "daily", "weekly", "monthly")
                                newRec = order[(order.indexOf(newRec) + 1) % order.size]
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                BwButton(
                    if (editingEvent != null) "Modifier l'événement" else "Ajouter le ${frDate(selected)}",
                    enabled = newName.isNotBlank()
                ) {
                    editingEvent?.let {
                        Store.updateEvent(it, selected, newTime.trim(), newName.trim(), newNn, newRec)
                    } ?: Store.addEvent(selected, newTime.trim(), newName.trim(), newNn, newRec)
                    resetForm()
                    bump()
                }
                if (editingEvent != null) {
                    Spacer(Modifier.height(8.dp))
                    BwButton("Annuler l'édition", ghost = true) { resetForm() }
                }
                Spacer(Modifier.height(30.dp))
            }
        }
    }
}
