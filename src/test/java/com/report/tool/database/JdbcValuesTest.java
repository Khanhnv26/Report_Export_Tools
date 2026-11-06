package com.report.tool.database;

import org.junit.jupiter.api.Test;
import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;
import java.sql.SQLException;
import java.sql.Time;

import static org.assertj.core.api.Assertions.*;

class JdbcValuesTest {
    @Test
    void materializesLobAndBinaryValuesBeforeConnectionCloses() throws Exception {
        assertThat(JdbcValues.normalize(new SerialClob("Customer note".toCharArray()))).isEqualTo("Customer note");
        assertThat(JdbcValues.normalize(new SerialBlob(new byte[]{1, 2, 3}))).isEqualTo("AQID");
        assertThat(JdbcValues.normalize(new byte[]{1, 2, 3})).isEqualTo("AQID");
        assertThat(JdbcValues.normalize(Time.valueOf("12:30:45"))).isEqualTo("12:30:45");
        assertThatThrownBy(() -> JdbcValues.normalize(new byte[1024 * 1024 + 1])).isInstanceOf(SQLException.class);
    }
}
