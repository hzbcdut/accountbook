package nt.ddeoid.accountbook.data.local

import androidx.room.TypeConverter
import nt.ddeoid.accountbook.data.local.entity.AccountType

/** Room 类型转换器。仅放可空字符串这种 Room 自身不直接支持的少量类型。 */
class Converters {

    @TypeConverter
    fun fromAccountType(value: AccountType): String = value.name

    @TypeConverter
    fun toAccountType(value: String): AccountType =
        runCatching { AccountType.valueOf(value) }.getOrDefault(AccountType.PHONE)
}