package dev.inkdeck.data.tasks

import androidx.room.TypeConverter

/**
 * Room type converters.
 *
 * Lists are stored as comma-separated text rather than JSON: there is no JSON library in the
 * dependency set, the values are integers and short tags, and the column stays readable in a
 * `sqlite3` dump. Tags are stripped of commas on the way in for the same reason.
 */
class Converters {

    @TypeConverter
    fun intListToString(value: List<Int>): String = value.joinToString(",")

    @TypeConverter
    fun stringToIntList(value: String?): List<Int> =
        value?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()

    @TypeConverter
    fun tagsToString(value: List<String>): String =
        value.joinToString(",") { it.replace(',', ' ').trim() }.trim(',')

    @TypeConverter
    fun stringToTags(value: String?): List<String> =
        value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    @TypeConverter
    fun repeatToString(value: RepeatRule): String = value.serialize()

    @TypeConverter
    fun stringToRepeat(value: String?): RepeatRule = RepeatRule.parse(value)

    @TypeConverter
    fun priorityToInt(value: Priority): Int = value.ordinal

    @TypeConverter
    fun intToPriority(value: Int): Priority = Priority.of(value)

    @TypeConverter
    fun statusToInt(value: TaskStatus): Int = value.ordinal

    @TypeConverter
    fun intToStatus(value: Int): TaskStatus =
        TaskStatus.entries.getOrElse(value) { TaskStatus.OPEN }
}
