/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.EADDRINUSE;
import static android.system.OsConstants.IPPROTO_UDP;
import static android.system.OsConstants.SOCK_DGRAM;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.INetd;
import android.net.IpSecManager;
import android.net.IpSecSpiResponse;
import android.net.IpSecTransform;
import android.net.IpSecUdpEncapResponse;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.system.ErrnoException;
import android.system.Os;

import java.io.FileDescriptor;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link IpSecService}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class IpSecServiceTest {

    private static final int DROID_SPI = 0xD1201D;
    private static final int MAX_NUM_ENCAP_SOCKETS = 100;
    private static final int MAX_NUM_SPIS = 100;
    private static final int TEST_UDP_ENCAP_INVALID_PORT = 100;
    private static final int TEST_UDP_ENCAP_PORT_OUT_RANGE = 100000;

    private static final InetAddress INADDR_ANY;

    static {
        try {
            INADDR_ANY = InetAddress.getByAddress(new byte[] {0, 0, 0, 0});
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    Context mMockContext;
    INetd mMockNetd;
    IpSecService.IpSecServiceConfiguration mMockIpSecSrvConfig;
    IpSecService mIpSecService;

    @Before
    public void setUp() throws Exception {
        mMockContext = mock(Context.class);
        mMockNetd = mock(INetd.class);
        mMockIpSecSrvConfig = mock(IpSecService.IpSecServiceConfiguration.class);
        mIpSecService = new IpSecService(mMockContext, mMockIpSecSrvConfig);

        // Injecting mock netd
        when(mMockIpSecSrvConfig.getNetdInstance()).thenReturn(mMockNetd);
    }

    @Test
    public void testIpSecServiceCreate() throws InterruptedException {
        IpSecService ipSecSrv = IpSecService.create(mMockContext);
        assertNotNull(ipSecSrv);
    }

    @Test
    public void testReleaseInvalidSecurityParameterIndex() throws Exception {
        try {
            mIpSecService.releaseSecurityParameterIndex(1);
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException e) {
        }
    }

    /** This function finds an available port */
    int findUnusedPort() throws Exception {
        // Get an available port.
        ServerSocket s = new ServerSocket(0);
        int port = s.getLocalPort();
        s.close();
        return port;
    }

    @Test
    public void testOpenAndCloseUdpEncapsulationSocket() throws Exception {
        int localport = findUnusedPort();

        IpSecUdpEncapResponse udpEncapResp =
                mIpSecService.openUdpEncapsulationSocket(localport, new Binder());
        assertNotNull(udpEncapResp);
        assertEquals(IpSecManager.Status.OK, udpEncapResp.status);
        assertEquals(localport, udpEncapResp.port);

        mIpSecService.closeUdpEncapsulationSocket(udpEncapResp.resourceId);
        udpEncapResp.fileDescriptor.close();

        // TODO: Added check for the resource tracker
    }

    @Test
    public void testOpenUdpEncapsulationSocketAfterClose() throws Exception {
        int localport = findUnusedPort();
        IpSecUdpEncapResponse udpEncapResp =
                mIpSecService.openUdpEncapsulationSocket(localport, new Binder());
        assertNotNull(udpEncapResp);
        assertEquals(IpSecManager.Status.OK, udpEncapResp.status);
        assertEquals(localport, udpEncapResp.port);

        mIpSecService.closeUdpEncapsulationSocket(udpEncapResp.resourceId);
        udpEncapResp.fileDescriptor.close();

        /** Check if localport is available. */
        FileDescriptor newSocket = Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        Os.bind(newSocket, INADDR_ANY, localport);
        Os.close(newSocket);
    }

    /**
     * This function checks if the IpSecService holds the reserved port. If
     * closeUdpEncapsulationSocket is not called, the socket cleanup should not be complete.
     */
    @Test
    public void testUdpEncapPortNotReleased() throws Exception {
        int localport = findUnusedPort();
        IpSecUdpEncapResponse udpEncapResp =
                mIpSecService.openUdpEncapsulationSocket(localport, new Binder());
        assertNotNull(udpEncapResp);
        assertEquals(IpSecManager.Status.OK, udpEncapResp.status);
        assertEquals(localport, udpEncapResp.port);

        udpEncapResp.fileDescriptor.close();

        FileDescriptor newSocket = Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        try {
            Os.bind(newSocket, INADDR_ANY, localport);
            fail("ErrnoException not thrown");
        } catch (ErrnoException e) {
            assertEquals(EADDRINUSE, e.errno);
        }
        mIpSecService.closeUdpEncapsulationSocket(udpEncapResp.resourceId);
    }

    @Test
    public void testOpenUdpEncapsulationSocketOnRandomPort() throws Exception {
        IpSecUdpEncapResponse udpEncapResp =
                mIpSecService.openUdpEncapsulationSocket(0, new Binder());
        assertNotNull(udpEncapResp);
        assertEquals(IpSecManager.Status.OK, udpEncapResp.status);
        assertNotEquals(0, udpEncapResp.port);
        mIpSecService.closeUdpEncapsulationSocket(udpEncapResp.resourceId);
        udpEncapResp.fileDescriptor.close();
    }

    @Test
    public void testOpenUdpEncapsulationSocketPortRange() throws Exception {
        try {
            mIpSecService.openUdpEncapsulationSocket(TEST_UDP_ENCAP_INVALID_PORT, new Binder());
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException e) {
        }

        try {
            mIpSecService.openUdpEncapsulationSocket(TEST_UDP_ENCAP_PORT_OUT_RANGE, new Binder());
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    public void testOpenUdpEncapsulationSocketTwice() throws Exception {
        int localport = findUnusedPort();

        IpSecUdpEncapResponse udpEncapResp =
                mIpSecService.openUdpEncapsulationSocket(localport, new Binder());
        assertNotNull(udpEncapResp);
        assertEquals(IpSecManager.Status.OK, udpEncapResp.status);
        assertEquals(localport, udpEncapResp.port);
        mIpSecService.openUdpEncapsulationSocket(localport, new Binder());

        IpSecUdpEncapResponse testUdpEncapResp =
                mIpSecService.openUdpEncapsulationSocket(localport, new Binder());
        assertEquals(IpSecManager.Status.RESOURCE_UNAVAILABLE, testUdpEncapResp.status);

        mIpSecService.closeUdpEncapsulationSocket(udpEncapResp.resourceId);
        udpEncapResp.fileDescriptor.close();
    }

    @Test
    public void testCloseInvalidUdpEncapsulationSocket() throws Exception {
        try {
            mIpSecService.closeUdpEncapsulationSocket(1);
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    public void testDeleteInvalidTransportModeTransform() throws Exception {
        try {
            mIpSecService.deleteTransportModeTransform(1);
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    public void testRemoveTransportModeTransform() throws Exception {
        ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(new Socket());
        mIpSecService.removeTransportModeTransform(pfd, 1);

        verify(mMockNetd).ipSecRemoveTransportModeTransform(pfd.getFileDescriptor());
    }

    @Test
    public void testValidateIpAddresses() throws Exception {
        String[] invalidAddresses =
                new String[] {"www.google.com", "::", "2001::/64", "0.0.0.0", ""};
        for (String address : invalidAddresses) {
            try {
                IpSecSpiResponse spiResp =
                        mIpSecService.reserveSecurityParameterIndex(
                                IpSecTransform.DIRECTION_OUT, address, DROID_SPI, new Binder());
                fail("Invalid address was passed through IpSecService validation: " + address);
            } catch (IllegalArgumentException e) {
            } catch (Exception e) {
                fail(
                        "Invalid InetAddress was not caught in validation: "
                                + address
                                + ", Exception: "
                                + e);
            }
        }
    }

    /**
     * This function checks if the number of encap UDP socket that one UID can reserve
     * has a reasonable limit.
     */
    @Test
    public void testSocketResourceTrackerLimitation() throws Exception {
        List<IpSecUdpEncapResponse> openUdpEncapSockets = new ArrayList<IpSecUdpEncapResponse>();
        // Reserve sockets until it fails.
        for (int i = 0; i < MAX_NUM_ENCAP_SOCKETS; i++) {
            IpSecUdpEncapResponse newUdpEncapSocket =
                    mIpSecService.openUdpEncapsulationSocket(0, new Binder());
            assertNotNull(newUdpEncapSocket);
            if (IpSecManager.Status.OK != newUdpEncapSocket.status) {
                break;
            }
            openUdpEncapSockets.add(newUdpEncapSocket);
        }
        // Assert that the total sockets quota has a reasonable limit.
        assertTrue(
                openUdpEncapSockets.size() > 0
                        && openUdpEncapSockets.size() < MAX_NUM_ENCAP_SOCKETS);

        // Try to reserve one more UDP encapsulation socket, and should fail.
        IpSecUdpEncapResponse extraUdpEncapSocket =
                mIpSecService.openUdpEncapsulationSocket(0, new Binder());
        assertNotNull(extraUdpEncapSocket);
        assertEquals(IpSecManager.Status.RESOURCE_UNAVAILABLE, extraUdpEncapSocket.status);

        // Close one of the open UDP encapsulation scokets.
        mIpSecService.closeUdpEncapsulationSocket(openUdpEncapSockets.get(0).resourceId);
        openUdpEncapSockets.get(0).fileDescriptor.close();
        openUdpEncapSockets.remove(0);

        // Try to reserve one more UDP encapsulation socket, and should be successful.
        extraUdpEncapSocket = mIpSecService.openUdpEncapsulationSocket(0, new Binder());
        assertNotNull(extraUdpEncapSocket);
        assertEquals(IpSecManager.Status.OK, extraUdpEncapSocket.status);
        openUdpEncapSockets.add(extraUdpEncapSocket);

        // Close open UDP sockets.
        for (IpSecUdpEncapResponse openSocket : openUdpEncapSockets) {
            mIpSecService.closeUdpEncapsulationSocket(openSocket.resourceId);
            openSocket.fileDescriptor.close();
        }
    }

    /**
     * This function checks if the number of SPI that one UID can reserve
     * has a reasonable limit.
     * This test does not test for both address families or duplicate SPIs because resource
     * tracking code does not depend on them.
     */
    @Test
    public void testSpiResourceTrackerLimitation() throws Exception {
        List<IpSecSpiResponse> reservedSpis = new ArrayList<IpSecSpiResponse>();
        // Return the same SPI for all SPI allocation since IpSecService only
        // tracks the resource ID.
        when(mMockNetd.ipSecAllocateSpi(
                        anyInt(),
                        eq(IpSecTransform.DIRECTION_OUT),
                        anyString(),
                        eq(InetAddress.getLoopbackAddress().getHostAddress()),
                        anyInt()))
                .thenReturn(DROID_SPI);
        // Reserve spis until it fails.
        for (int i = 0; i < MAX_NUM_SPIS; i++) {
            IpSecSpiResponse newSpi =
                    mIpSecService.reserveSecurityParameterIndex(
                            0x1,
                            InetAddress.getLoopbackAddress().getHostAddress(),
                            DROID_SPI + i,
                            new Binder());
            assertNotNull(newSpi);
            if (IpSecManager.Status.OK != newSpi.status) {
                break;
            }
            reservedSpis.add(newSpi);
        }
        // Assert that the SPI quota has a reasonable limit.
        assertTrue(reservedSpis.size() > 0 && reservedSpis.size() < MAX_NUM_SPIS);

        // Try to reserve one more SPI, and should fail.
        IpSecSpiResponse extraSpi =
                mIpSecService.reserveSecurityParameterIndex(
                        0x1,
                        InetAddress.getLoopbackAddress().getHostAddress(),
                        DROID_SPI + MAX_NUM_SPIS,
                        new Binder());
        assertNotNull(extraSpi);
        assertEquals(IpSecManager.Status.RESOURCE_UNAVAILABLE, extraSpi.status);

        // Release one reserved spi.
        mIpSecService.releaseSecurityParameterIndex(reservedSpis.get(0).resourceId);
        reservedSpis.remove(0);

        // Should successfully reserve one more spi.
        extraSpi =
                mIpSecService.reserveSecurityParameterIndex(
                        0x1,
                        InetAddress.getLoopbackAddress().getHostAddress(),
                        DROID_SPI + MAX_NUM_SPIS,
                        new Binder());
        assertNotNull(extraSpi);
        assertEquals(IpSecManager.Status.OK, extraSpi.status);

        // Release reserved SPIs.
        for (IpSecSpiResponse spiResp : reservedSpis) {
            mIpSecService.releaseSecurityParameterIndex(spiResp.resourceId);
        }
    }
}
