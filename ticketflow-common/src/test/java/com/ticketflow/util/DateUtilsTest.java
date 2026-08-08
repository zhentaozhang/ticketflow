package com.ticketflow.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DateUtilsTest {

    private static final long SECOND_MILLIS = 1000L;

    private TimeZone originalTimeZone;

    @BeforeEach
    void saveDefaultTimeZone() {
        originalTimeZone = TimeZone.getDefault();
    }

    @AfterEach
    void restoreDefaultTimeZone() {
        TimeZone.setDefault(originalTimeZone);
    }

    // ═══════════════ getWeekDateEnd / getWeekDateStart ═══════════════
    // 2026-08-03 周一，2026-08-05 周三，2026-08-09 周日，2026-08-10 周一

    private Date at(String dateTime) {
        return DateUtils.getDate(dateTime, DateUtils.FORMAT_SECOND);
    }

    private Date endOfDay(String date) {
        return new Date(at(date + " 23:59:59").getTime() + 999L);
    }

    @Test
    void getWeekDateEnd_sunday_returnsSameDay235959999() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));

        Date result = DateUtils.getWeekDateEnd(at("2026-08-09 10:30:00"));

        assertEquals(endOfDay("2026-08-09").getTime(), result.getTime());
    }

    @Test
    void getWeekDateEnd_wednesday_returnsSundayEnd() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));

        Date result = DateUtils.getWeekDateEnd(at("2026-08-05 15:30:00"));

        assertEquals(endOfDay("2026-08-09").getTime(), result.getTime());
    }

    @Test
    void getWeekDateEnd_monday_returnsSundayEndOfSameWeek() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));

        Date result = DateUtils.getWeekDateEnd(at("2026-08-10 09:00:00"));

        assertEquals(endOfDay("2026-08-16").getTime(), result.getTime());
    }

    @Test
    void getWeekDateStart_wednesday_returnsMondayStart() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));

        Date result = DateUtils.getWeekDateStart(at("2026-08-05 15:30:00"));

        assertEquals(at("2026-08-03 00:00:00").getTime(), result.getTime());
    }

    @Test
    void getWeekDateStart_monday_returnsSameDayStart() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));

        Date result = DateUtils.getWeekDateStart(at("2026-08-10 09:00:00"));

        assertEquals(at("2026-08-10 00:00:00").getTime(), result.getTime());
    }

    @Test
    void getWeekDateStartEnd_null_returnsNull() {
        assertNull(DateUtils.getWeekDateStart(null));
        assertNull(DateUtils.getWeekDateEnd(null));
    }

    @Test
    void getWeekDateList_containsSevenDaysFromMondayToSunday() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));

        List<Date> list = DateUtils.getWeekDateList(at("2026-08-05 15:30:00"));

        assertEquals(7, list.size());
        assertEquals(at("2026-08-03 00:00:00").getTime(), list.get(0).getTime());
        // getBetweenDateList 按天生成，每项均为当天 00:00:00
        assertEquals(at("2026-08-09 00:00:00").getTime(), list.get(6).getTime());
    }

    // ═══════════════ now() 北京时区（本次修复核心） ═══════════════

    @Test
    void now_underUtcJvm_returnsCurrentInstant() {
        // JVM 默认时区改为 UTC 后，now() 的返回值必须不受影响：
        // 修复前 parse 使用 JVM 默认时区，UTC 环境下会整体偏移 +8h
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        long expected = (System.currentTimeMillis() / SECOND_MILLIS) * SECOND_MILLIS;

        long diff = Math.abs(DateUtils.now().getTime() - expected);
        assertTrue(diff < 1500L, "now() 偏离当前时刻 " + diff + "ms");
    }

    @Test
    void now_underShanghaiJvm_returnsCurrentInstant() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));

        long expected = (System.currentTimeMillis() / SECOND_MILLIS) * SECOND_MILLIS;

        long diff = Math.abs(DateUtils.now().getTime() - expected);
        assertTrue(diff < 1500L, "now() 偏离当前时刻 " + diff + "ms");
    }

    @Test
    void now_returnsBeijingWallClock_underUtcJvm() throws Exception {
        // 语义校验：now() 返回的 Date 用北京时间读取时，必须是北京墙钟的当前秒
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        SimpleDateFormat beijingFormat = new SimpleDateFormat(DateUtils.FORMAT_SECOND);
        beijingFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));

        String beijingWall = beijingFormat.format(DateUtils.now());
        long expected = (System.currentTimeMillis() / SECOND_MILLIS) * SECOND_MILLIS;
        long actual = beijingFormat.parse(beijingWall).getTime();
        long diff = Math.abs(actual - expected);
        assertTrue(diff < 1500L, "now() 北京时间读数偏离当前时刻 " + diff + "ms");
    }

    @Test
    void nowStr_underUtcJvm_returnsBeijingTimeString() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // nowStr() 内部以 Asia/Shanghai 解析，测试里也用显式北京时区解析回时间戳
        SimpleDateFormat beijingFormat = new SimpleDateFormat(DateUtils.FORMAT_SECOND);
        beijingFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));

        String result = DateUtils.nowStr();
        assertNotNull(result);

        long expected = (System.currentTimeMillis() / SECOND_MILLIS) * SECOND_MILLIS;
        long actual = beijingFormat.parse(result).getTime();
        long diff = Math.abs(actual - expected);
        assertTrue(diff < 1500L, "nowStr() 偏离北京时间 " + diff + "ms");
    }
}
