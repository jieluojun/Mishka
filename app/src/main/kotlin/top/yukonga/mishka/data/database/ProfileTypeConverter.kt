package top.yukonga.mishka.data.database

import androidx.room3.ColumnTypeConverter
import top.yukonga.mishka.domain.model.ProfileType

/** 两个方法只由 Room 生成的代码调用，静态搜索查不到引用，**不要当死代码删**。 */
class ProfileTypeConverter {
    @ColumnTypeConverter
    fun fromType(value: ProfileType): String = value.name

    @ColumnTypeConverter
    fun toType(value: String): ProfileType = ProfileType.fromStringOrDefault(value)
}
