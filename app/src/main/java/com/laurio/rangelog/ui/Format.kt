package com.laurio.rangelog.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val longDate = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
private val shortDate = DateTimeFormatter.ofPattern("M/d/yy")

fun LocalDate.pretty(): String = format(longDate)
fun LocalDate.short(): String = format(shortDate)
fun Long.epochDayShort(): String = LocalDate.ofEpochDay(this).short()
fun Long.epochDayPretty(): String = LocalDate.ofEpochDay(this).pretty()

fun Int.withCommas(): String = "%,d".format(this)

/** Material date pickers work in UTC milliseconds. */
fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
fun Long.pickerMillisToDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
