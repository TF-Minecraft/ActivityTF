package net.tfminecraft.activitytf.models;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferralDataTest {

    @Test
    void minutesNeverGoNegativeOrOverflow() {
        ReferralData data = new ReferralData("A", 5);
        data.setMinutes(-3);
        assertEquals(0, data.minutes());
        data.addMinute();
        assertEquals(1, data.minutes());
        data.setMinutes(Integer.MAX_VALUE);
        data.addMinute();
        assertEquals(Integer.MAX_VALUE, data.minutes());
    }

    @Test
    void connectionsKeepTheMostRecentSixteen() {
        ReferralData data = new ReferralData("A", 0);
        for (int index = 0; index < 20; index++) {
            assertTrue(data.addIp("ip" + index));
        }
        assertEquals(ReferralData.MAX_IPS, data.ips().size());
        assertFalse(data.ips().contains("ip3"));
        assertFalse(data.addIp("ip4"), "seen again moves it to the end");
        assertEquals("ip4", data.ipList().get(ReferralData.MAX_IPS - 1));

        ReferralData other = new ReferralData("B", 0);
        assertFalse(data.sharesIp(other));
        other.addIp("ip10");
        assertTrue(data.sharesIp(other));
    }

    @Test
    void linksAndRareCounts() {
        ReferralData data = new ReferralData("A", 0);
        UUID inviter = UUID.randomUUID();
        data.link(inviter, 7);
        assertEquals(inviter, data.invitedBy());
        assertEquals(7, data.linkedAt());
        data.link(null, 9);
        assertNull(data.invitedBy());
        assertEquals(0, data.linkedAt());

        data.setRareClaims("act_1", 2);
        assertEquals(2, data.rareClaims("act_1"));
        data.setRareClaims("act_1", 0);
        assertTrue(data.rareClaims().isEmpty());
        assertEquals(0, data.rareClaims("act_2"));
        assertEquals(List.of(), data.ipList());
        data.setLastSeen(3);
        assertEquals(3, data.lastSeen());
        assertTrue(data.ipPartners().isEmpty());
    }
}
