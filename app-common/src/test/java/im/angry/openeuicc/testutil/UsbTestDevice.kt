package im.angry.openeuicc.testutil

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

fun usbTestDevice(): UsbDevice {
    fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
        UsbEndpoint::class.java,
        ClassParameter.from(Int::class.javaPrimitiveType, address),
        ClassParameter.from(Int::class.javaPrimitiveType, 2),
        ClassParameter.from(Int::class.javaPrimitiveType, 64),
        ClassParameter.from(Int::class.javaPrimitiveType, 0)
    )
    val iface = Shadow.newInstanceOf(UsbInterface::class.java)
    ReflectionHelpers.setField(iface, "mClass", 11)
    ReflectionHelpers.setField(iface, "mEndpoints", arrayOf(endpoint(0x81), endpoint(0x02)))
    return Shadow.newInstanceOf(UsbDevice::class.java).also {
        ReflectionHelpers.setField(it, "mName", "/dev/bus/usb/001/002")
        ReflectionHelpers.setField(it, "mVendorId", 0x2ce3)
        ReflectionHelpers.setField(it, "mProductId", 0x9563)
        ReflectionHelpers.setField(it, "mInterfaces", arrayOf(iface))
    }
}
