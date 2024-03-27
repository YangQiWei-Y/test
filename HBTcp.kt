package com.allynav.precisionag.Communication.tcp

import android.util.Log
import com.allynav.precisionag.Application
import com.allynav.precisionag.Classes.DataBaseOperator
import com.allynav.precisionag.Classes.Task.Interactive.TracksCache
import com.allynav.precisionag.Classes.Task.LineWork
import com.allynav.precisionag.Enum.GuidanceLineType
import com.allynav.precisionag.Enum.StartType
import com.allynav.precisionag.Function.CommFunction
import com.allynav.precisionag.Function.FormatFunction
import com.allynav.precisionag.HAL.Gnss.UTM
import com.allynav.precisionag.Module.Guidance.Guidance
import com.allynav.precisionag.Module.Tilling.Tilling
import com.allynav.precisionag.PrecisionAg
import com.allynav.precisionag.file.FilePaths
import com.allynav.precisionag.utils.ConstantUtils
import com.allynav.precisionag.utils.DataTool
import com.allynav.precisionag.utils.FileUtils
import com.allynav.precisionag.utils.HardInfo
import com.allynav.precisionag.utils.thread.ThreadHelper
import com.tamsiree.rxkit.RxNetTool
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class HBTcp {
    private var ip = "223.75.53.178"
    private var port = 5000
    private var hbNameplate = ""
    private var sock: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var timer: ScheduledFuture<*>? = null

    private var isTest = false
    private var testDataTag = 0 //0自动驾驶   1作业检测

    //正式平台sn 1322050653    1322030434    测试平台sn 1322051819    1322081714
    private var sn = DataTool.getStrResult(DataTool.decimal2Hex("1322050653".toLong(), 8), 4)

    private var picCount = 0
    private var picSize = 0
    private var lengthA = 450
    private var picPath = FilePaths.CameraPath.getChildPath("backstagePic.png")
    private var sendIndex = 0
    private var mByteList: ArrayList<ByteArray> = ArrayList()
    private var isSendPic = false
    private var picStopCount = 0

    private var socketType = 0 //0自动驾驶   1作业检测
    private var hubeiCode: String = ""
    private var picExist: Boolean = false

    private var serialNumber = 0
    private var picNumber = byteArrayOf()
    private var isPicResponse = false;

    fun parseData(json: JSONObject? = null) {
        if (json != null) {
            val url = json.get("ip").toString()
            ip = url.split(":")[0]
            port = url.split(":")[1].toInt()
            val nameplate = json.get("nameplate").toString()
            hbNameplate = if (nameplate.length > 10) {
                if (nameplate.contains("LSAF") || nameplate.contains("HT")) {
                    nameplate.substring(nameplate.length - 9)
                } else {
                    nameplate.substring(nameplate.length - 8)
                }
            } else {
                PrecisionAg.SN
            }
            PrecisionAg.Instance().sysPara.hbNameplate = hbNameplate
        } else {
            hbNameplate = PrecisionAg.Instance().sysPara.hbNameplate
        }
        hubeiCode = PrecisionAg.Instance().sysPara.hbCode
        if ("".equals(hubeiCode)) {
            hubeiCode = "00000000"
        }

        if (isTest) {
            //new Socket("223.75.53.178", 5000);//正式端口
            //new Socket("111.47.18.22", 10028);//测试端口
            ip = "223.75.53.178"
            port = 5000
            hbNameplate = "222010232"   //222010232  测试铭牌号
        } else {
            //测试ip
            //ip = "111.47.18.22"
            //port = 10028
        }
        DataTool.LogHuBei("ip:" + ip + " port:" + port + " hbNameplate:" + hbNameplate  + " hubeiCode:" + hubeiCode)
        ThreadHelper.cachedThreadPool.execute { startWork() }
    }

    /*
    *
    * 设置socket连接类型
    * 0：自动驾驶类型
    * 1：作业检测类型
    *
    * */
    fun setSocketType(type: Int) {
        socketType = type
        testDataTag = type
    }

    fun startWork() {
        TerminalTcp.instance.startTcpConnect(ip, port, object : TerminalTcp.tcpConnectCallback {
            override fun onConnectSuccess(socket: Socket, outx: OutputStream, inx: InputStream) {
                sock = socket
                outputStream = outx
                inputStream = inx
                serialNumber = 0
                if (socketType == 0) {
                    sendByteData(DataTool.getSendByte(getLoginData(0)))
                } else {
                    sendByteData(DataTool.getSendByte(getLoginData(1)))
                }
                sendByteData(setHeartbeatData(0))
                startTask()
                receiveMessage()
            }

            override fun onConnectFail(e: Exception) {
                //startWork()
            }
        })
    }

    fun startTask() {
        timer?.cancel(true)
        timer = ThreadHelper.scheduledThreadPool
            .scheduleWithFixedDelay({ upLoadDataTask() }, 1000, 2000, TimeUnit.MILLISECONDS)
    }

    fun endTask() {
        timer?.cancel(true)
    }

    /*
    * 定时器任务
    */
    private fun upLoadDataTask() {
        DataTool.LogHuBei("state:" + sock?.isConnected)
        //saveUnUploadTrack(CommFunction.bytesToHex(getTrackLocationData()).replace(" ",""))
        if (!RxNetTool.isNetworkAvailable(Application.getInstance().applicationContext)) {
            if (socketType == 0) {
                //保存自动驾驶离线数据
                saveUnUploadTrack(CommFunction.bytesToHex(getTrackLocationData()).replace(" ",""))
            } else {
                //保存作业检测离线数据
                saveUnUploadTrack(CommFunction.bytesToHex(getWorkLocationData()).replace(" ",""))
            }
            return
        }
        if (sock?.isConnected == true) {
            if (socketType == 0) {
                sendByteData(getTrackLocationData())   //自动驾驶数据
            } else {
                sendByteData(getWorkLocationData())    //作业检测数据
            }
            if (picCount == 900) {
                sendPicThread()
            }
            picCount++
        } else {
            Log.e("qwq", "断开重连")
            endTask()
            startWork()
        }
        if(picCount%10==0){
            sendByteData(getNullData())
        }
    }

    /*
    * 获取当前连接状态
    * */

    fun getConnecteState(): Boolean? {
        return sock?.isConnected
    }

    private fun getWorkLocationData(): ByteArray {
        var data = getSn(1)
        val time = DataTool.decimal2Hex((System.currentTimeMillis() / 1000).toInt().toLong(), 8)
        data += DataTool.getStrResult(time, 4)
        if (HardInfo.mGuidanceStatu != null && HardInfo.mGuidanceStatu.FootPoint != null) {
            val footPoint = HardInfo.mGuidanceStatu.FootPoint
            UTM.PlaneToGeo(footPoint, PrecisionAg.Instance().zone)
            val latitude = DataTool.decimal2Hex(
                FormatFunction.decimal0.format(
                    DataTool.getddmm(footPoint.latitude).toDouble() * 10000
                ).toLong(), 8
            )
            val longitude = DataTool.decimal2Hex(
                FormatFunction.decimal0.format(
                    DataTool.getddmm(footPoint.longitude).toDouble() * 10000
                ).toLong(), 8
            )
            data = data + DataTool.getStrResult(latitude, 4) + DataTool.getStrResult(longitude, 4)
        }
        if (HardInfo.mPositionData != null) {
            val alt = DataTool.decimal2Hex(
                FormatFunction.decimal0.format(HardInfo.mPositionData.alt).toLong(), 8
            )
            val speed = DataTool.decimal2Hex(
                FormatFunction.decimal0.format(HardInfo.mPositionData.speed * 10).toLong(), 4
            )
            val azimuth = DataTool.decimal2Hex(
                FormatFunction.decimal0.format(
                    DataTool.getddmm(HardInfo.mPositionData.azimuth).toDouble()
                ).toLong(), 4
            )
            data = data + DataTool.getStrResult(alt, 4) + DataTool.getStrResult(
                azimuth,
                2
            ) + DataTool.getStrResult(speed, 2)
        }
        if (StartType.isTillingDepth()) {
            data += DataTool.getStrResult(
                DataTool.decimal2Hex(
                    Tilling.Instance().tillHeight.toInt().toLong(), 4
                ), 2
            )
        }
        //data += "1400" //测试
        data += "1000"
        data += "1000"
        if (StartType.isTillingDepth()) {
            data += DataTool.getStrResult(
                DataTool.decimal2Hex(
                    Tilling.Instance().tillHeight.toInt().toLong(), 4
                ), 2
            )
        }
        //data += "1400" //测试
        data += "0001"   //位置附加信息 工作识别码
        data += getWorkType()
        data += "0104"      //机具识别码信息
        data += DataTool.getStrResult(DataTool.decimal2Hex(hubeiCode.toLong(), 8), 4)  //机具识别码信息

        data += "0204"  //工作状态
        data += "0000"  //补推数据条数
        data += "00"  //保留位
        data += "E0"  //工作状态 1工作中 0未工作   定位标识 00无法定位 01普通定位 11高精度定位    数据类型:补推1 和 实时0
        val b = DataTool.getSendByte(data)
        return DataTool.mergeBytes(DataTool.mergeBytes(getByteHead(1, b.size), b), byteArrayOf(0x7f))
    }

    fun getWorkType(): String {
        var result = "00"
        val workModeType = PrecisionAg.Instance().sysPara.mFarmImplement.mWorkType.workNameType
        if (workModeType == 0) {
            result = "07"
        } else if (workModeType == 1) {
            result = "05"
        } else if (workModeType == 5) {
            result = "00"
        } else if (workModeType == 11) {
            result = "02"
        } else if (workModeType == 12) {
            result = "08"
        } else if (workModeType == 13) {
            result = "06"
        } else if (workModeType == 15) {
            result = "03"
        } else if (workModeType == 17) {
            result = "01"
        } else {
            result = "00"
        }
        return result
    }

    private fun getTrackLocationData(): ByteArray {
        var data = ""
        if (HardInfo.mGuidanceStatu != null && HardInfo.mGuidanceStatu.FootPoint != null) {
            val footPoint = HardInfo.mGuidanceStatu.FootPoint
            UTM.PlaneToGeo(footPoint, PrecisionAg.Instance().zone)
            val latitude = DataTool.decimal2Hex(
                FormatFunction.decimal0.format(
                    DataTool.getddmm(footPoint.latitude).toDouble() * 10000
                ).toLong(), 8
            )
            val longitude = DataTool.decimal2Hex(
                FormatFunction.decimal0.format(
                    DataTool.getddmm(footPoint.longitude).toDouble() * 10000
                ).toLong(), 8
            )
            data = data + DataTool.getStrResult(longitude, 4) + DataTool.getStrResult(latitude, 4)
        }
        if (HardInfo.mPositionData != null) {
            val alt = DataTool.decimal2Hex(
                FormatFunction.decimal0.format(HardInfo.mPositionData.alt).toLong(), 8
            )
            val speed = DataTool.decimal2Hex(
                FormatFunction.decimal0.format(HardInfo.mPositionData.speed * 10).toLong(), 4
            )
            val azimuth = DataTool.decimal2Hex(
                FormatFunction.decimal0.format(
                    DataTool.getddmm(HardInfo.mPositionData.azimuth).toDouble()
                ).toLong(), 4
            )
            data = data + DataTool.getStrResult(alt, 4) + DataTool.getStrResult(
                speed,
                2
            ) + DataTool.getStrResult(azimuth, 2)
        }
        val b = DataTool.getSendByte(data)
        return DataTool.mergeBytes(DataTool.mergeBytes(getByteHead(0, b.size), b), byteArrayOf(0x7f))
    }

    fun sendByteData(byte: ByteArray) {
        try {
            outputStream?.write(DataTool.translateByte(byte))
            Log.e("qwq", "sendByteData:" + socketType + "  " + CommFunction.bytesToHex(byte).replace(" ", ""))
            DataTool.LogHuBei("sendByteData:" + socketType + "  " + CommFunction.bytesToHex(byte).replace(" ", ""))
        } catch (e: Exception) {
            DataTool.LogHuBei("e:" + e.message)
            Log.e("qwq", "e:" + e.message)
            endTask()
            startWork()
        }
    }

    fun receiveMessage() {
        ThreadHelper.scheduledThreadPool.scheduleWithFixedDelay({ readMsg() }, 0, 100, TimeUnit.MILLISECONDS)
    }

    fun readMsg() {
        val bu = ByteArray(1024)
        val count = inputStream?.read(bu)
        if (count != null) {
            if (count > 0) {
                var begin  = 0
                for (i in 0 until count){
                    if(bu[i].toInt()==0x7f){
                        if(begin!=i && abs(begin - i) != 1){
                            //Log.e("qwq","msg:" + CommFunction.bytesToHex(msgList.get(i)).replace(" ", ""))
                            response(DataTool.restoreByte(bu.copyOfRange(begin,i+1)))
                        }
                        begin = i
                    }
                }
            }
        }
    }


    /*
    *
    * 获取SN或者铭牌号
    * type = 1 为sn   作业检测标识
    * type = 0 为铭牌号  自动驾驶标识
    * */
    fun getSn(type: Int): String {
        return if (isTest) {
            if (testDataTag == 0) {
                DataTool.getStrResult(DataTool.decimal2Hex(hbNameplate.toLong(), 8), 4)
            } else {
                sn
            }
        } else {
            if (socketType == 0) {
                DataTool.getStrResult(DataTool.decimal2Hex(hbNameplate.toLong(), 8), 4)
            } else {
                DataTool.getStrResult(DataTool.decimal2Hex(PrecisionAg.SN.toLong(), 8), 4)
            }
        }
    }

    fun getLoginData(type: Int): String {
        var result = "7f"
        result += "16"
        result += "2000"
        result += "0100"
        var data = getSn(type)
        data += "74657374"
        data += "74657374"
        data += "74657374"
        data += "74657374"
        data += "74657374"
        result += "1800"
        result += data
        result += "7f"
        //DataTool.LogHuBei(result)
        return result
    }

    private fun sendPicThread() {
        ThreadHelper.cachedThreadPool.execute {
            isSendPic = true
            sendIndex = 0
            picCount = 0
            picStopCount = 0
            splitPic()
            Thread.sleep(500)
            if(picExist){
                sendByteData(DataTool.getSendByte(sendPicBeginData()))
                Thread.sleep(500)
                sendBitPic()
            }
        }
    }

    private fun sendPicBeginData(): String {
        var result = "7f"
        result += "16"
        result += "3500"
        result += "0000"
        result += "0d00"
        var data = getSn(1)
        data += "01"
        data += "0101"
        data += "06"
        data += DataTool.getStrResult(DataTool.decimal2Hex(System.currentTimeMillis(), 12), 6)
        data += DataTool.getStrResult(DataTool.decimal2Hex(picSize.toLong(), 8), 4)
        result += data
        result += "7f"
        return result
    }

    fun sendPicEndData(): String {
        var result = "7f"
        result += "16"
        result += "3700"
        result += "0500"
        result += "1400"
        var data = getSn(1)
        data += DataTool.md5HashCode(picPath)
        result += data
        result += "7f"
        return result
    }

    private fun sendBitPic() {
        val b1 = mByteList[sendIndex]
        val b2 = DataTool.mergeBytes(DataTool.mergeBytes(getPicByteHead(b1.size), b1), byteArrayOf(0x7f))
        picNumber = b2.clone()
        isPicResponse = false
        sendByteData(b2)
        sendIndex++
    }

    private fun splitPic() {
        mByteList.clear()
        if (PrecisionAg.Instance().isNewTable) {
            picPath = FilePaths.CameraPicPath.path
            val picDir = FilePaths.CameraPicPath.file
            if (picDir.isDirectory && picDir.exists()) {
                val mList = picDir.listFiles()
                if (mList != null) {
                    if (mList.size > 0) {
                        var picname = mList[0].name
                        for (f in mList) {
                            if (f.length() > 50000) {
                                if (picname.substring(8).compareTo(f.name.substring(8)) == -1 && f.name.substring(6, 7).toInt() == 0) {
                                    picname = f.name
                                } else {
                                    picPath = f.path
                                }
                            }
                        }
                    }
                }
            }
        } else {
            if (File(FilePaths.CameraPath.getChildPath("backstagePic.png")).exists()) {
                picPath = FilePaths.CameraPath.getChildPath("backstagePic.png")
            } else if (File(FilePaths.CameraPath.getChildPath("backstagePic2.png")).exists()) {
                picPath = FilePaths.CameraPath.getChildPath("backstagePic2.png")
            }
        }
        if (File(picPath).exists()) {
            picExist = true
            val picByte = DataTool.image2byte(picPath)
            picSize = picByte.size
            val picByteCount = picByte.size / lengthA
            mByteList = ArrayList()
            for (i in 0 until picByteCount + 1) {
                val bytes: ByteArray = if (i == picByteCount) {
                    ByteArray(picByte.size % lengthA)
                } else {
                    ByteArray(lengthA)
                }
                mByteList.add(bytes)
            }
            for (i in picByte.indices) {
                val i1 = i / lengthA
                val i2 = i % lengthA
                mByteList[i1][i2] = picByte[i]
            }
        } else {
            picExist = false
        }
        //Log.e("qwq","picSize:" + picSize)
    }

    private fun getPicByteHead(length: Int): ByteArray {
        val id = getSn(1)
        val i = length + 8
        val result = "7f" + "16" + "3600" + "0500" + DataTool.getStrResult(DataTool.decimal2Hex(i.toLong(), 4), 2) + id +
                DataTool.getStrResult(DataTool.decimal2Hex(sendIndex.toLong(), 4), 2) + DataTool.getStrResult(DataTool.decimal2Hex(length.toLong(), 4), 2) + "" + ""
        return DataTool.getSendByte(result)
    }

    /*
    * type 0自动驾驶帧头  1作业检测帧头
    * length  帧数据长度
    * */
    private fun getByteHead(type: Int, length: Int): ByteArray {
        val number: String = when (type) {
            0 -> {
                "0004"
            }

            1 -> {
                "2100"
            }

            else -> {
                "3500"
            }
        }
        val result = "7f" + "16" + number + "0500" + DataTool.getStrResult(
            DataTool.decimal2Hex(
                length.toLong(),
                4
            ), 2
        )
        return DataTool.getSendByte(result)
    }

    //设置心跳
    private fun setHeartbeatData(type: Int): ByteArray {
        var result = "7f"
        result += "16"
        result += "2400"
        result += "0000"
        result += "0C00"
        var data = getSn(type)
        data += "0a000000"
        data += "0a000000"
        result += data
        result += "7f"
        return DataTool.getSendByte(result)
    }

    //空帧
    private fun getNullData(): ByteArray {
        var result = "7f"
        result += "16"
        result += "0000"
        result += "0000"
        result += "0000"
        result += "7f"
        return DataTool.getSendByte(result)
    }

    /*
   *
   * 新建作业数据
   * */
    private fun getNewWorkData(): ByteArray {
        var result = "7f"
        result += "16" //版本
        result += "0104" //帧号16
        result += "0000" //帧流水号16
        var data = ""
        data += getSn(0) //终端ID 4
        if (PrecisionAg.Instance().work !== null) {
            val workName = PrecisionAg.Instance().work.Name
            //作业名称长度 1
            val nameByte = workName.toByteArray().reversedArray()
            data += DataTool.getStrResult(
                DataTool.decimal2Hex(
                    nameByte.size.toLong(),
                    4
                ), 2
            )
            //作业名称
            data += DataTool.getStrResult(
                CommFunction.bytesToHex(nameByte).replace(" ", ""),
                nameByte.size
            )

            val farmName = PrecisionAg.Instance().work.FarmName
            val farmNameArray = farmName.toByteArray().reversedArray()
            //田块名称长度 1
//            data += DataTool.getStrResult(
//                DataTool.decimal2Hex(
//                    farmNameArray.size.toLong(),
//                    4
//                ), 2
//            )
            data += "01"
            //田块名称
            //data += DataTool.getStrResult(CommFunction.bytesToHex(farmNameArray).replace(" ",""),farmNameArray.size)
            data += "66"

            //作业类型号
            var type = "01"
            when (PrecisionAg.Instance().work.Type) {
                GuidanceLineType.StraightLine -> {
                    //直线
                    type = "01"
                }

                GuidanceLineType.CurveLine -> {
                    //曲线
                    type = "02"
                }

                GuidanceLineType.Concentric -> {
                    //圆
                    type = "03"
                }

            }
            data += type

            //作业内容
            val workModeType = PrecisionAg.Instance().sysPara.mFarmImplement.mWorkType.workNameType
            var workType = "01"
            if (workModeType == 0) {
                workType = "01"
            } else if (workModeType == 7) {
                workType = "00"
            } else if (workModeType == 14) {
                workType = "02"
            } else if (workModeType == 15) {
                workType = "03"
            } else {
                workType = "04"
            }
            data += workType

            //农具宽度
            val farmWidth = PrecisionAg.Instance().sysPara.mFarmImplement.ToolWidth * 100
            data += DataTool.getStrResult(
                DataTool.decimal2Hex(
                    farmWidth.toLong(),
                    4
                ), 2
            )

            //农具侧向偏移
            if (PrecisionAg.Instance().sysPara.mFarmImplement != null) {
                val DirectionOffset =
                    PrecisionAg.Instance().sysPara.mFarmImplement.DirectionOffset * PrecisionAg.Instance().sysPara.mFarmImplement.direction * 100
                data += DataTool.getStrResult(
                    DataTool.decimal2Hex(
                        DirectionOffset.toLong(),
                        4
                    ), 2
                )
            }

            data += "0000" //农具前后偏移 2

            //作业面积
            data += "00000000"

            //作业状态
            val isRunning = if (ConstantUtils.mCarRunning) "00" else "01"
            data += isRunning

            val pointNum = if (type == "03") {
                "03"
            } else {
                "02"
            }
            data += pointNum

            //A点
            val pointA = Guidance.Instance().pointA
            UTM.PlaneToGeo(pointA, PrecisionAg.Instance().zone)
            val pointAlatitude = DataTool.decimal2Hex(
                FormatFunction.decimal0.format(
                    DataTool.getddmm(pointA.latitude).toDouble() * 10000
                ).toLong(), 8
            )
            val pointAlongitude = DataTool.decimal2Hex(
                FormatFunction.decimal0.format(
                    DataTool.getddmm(pointA.longitude).toDouble() * 10000
                ).toLong(), 8
            )
            data = data + DataTool.getStrResult(pointAlongitude, 4) + DataTool.getStrResult(
                pointAlatitude,
                4
            )

            //B点
            val pointB = Guidance.Instance().pointB
            UTM.PlaneToGeo(pointB, PrecisionAg.Instance().zone)
            val pointBlatitude = DataTool.decimal2Hex(
                FormatFunction.decimal0.format(
                    DataTool.getddmm(pointB.latitude).toDouble() * 10000
                ).toLong(), 8
            )
            val pointBlongitude = DataTool.decimal2Hex(
                FormatFunction.decimal0.format(
                    DataTool.getddmm(pointB.longitude).toDouble() * 10000
                ).toLong(), 8
            )
            data = data + DataTool.getStrResult(pointBlongitude, 4) + DataTool.getStrResult(
                pointBlatitude,
                4
            )
            if (type == "03") {
                //圆三个点
                val pointC = Guidance.Instance().pointC
                UTM.PlaneToGeo(pointC, PrecisionAg.Instance().zone)
                val pointClatitude = DataTool.decimal2Hex(
                    FormatFunction.decimal0.format(
                        DataTool.getddmm(pointC.latitude).toDouble() * 10000
                    ).toLong(), 8
                )
                val pointClongitude = DataTool.decimal2Hex(
                    FormatFunction.decimal0.format(
                        DataTool.getddmm(pointC.longitude).toDouble() * 10000
                    ).toLong(), 8
                )
                data = data + DataTool.getStrResult(pointClongitude, 4) + DataTool.getStrResult(
                    pointClatitude,
                    4
                )
            }
        }
        //result += "1e00" //帧数据长度16
        result += DataTool.getStrResult(
            DataTool.decimal2Hex((data.length / 2).toLong(), 4),
            2
        ) //帧数据长度16
        result += data //帧数据
        result += "7f"
        return DataTool.getSendByte(result)
    }

    /*
    * 更新作业数据
    * */
    private fun getUpdataWork(lineWork: LineWork): ByteArray {
        var result = "7f"
        result += "16" //版本
        result += "0204" //帧号16
        result += "0000" //帧流水号16
        var data = ""
        data += getSn(0) //终端ID 4

        //作业名称长度
        val nameArray = lineWork.Name.toByteArray().reversedArray()
        data += DataTool.getStrResult(
            DataTool.decimal2Hex(
                nameArray.size.toLong(),
                4
            ), 2
        )
        //作业名称
        data += DataTool.getStrResult(
            CommFunction.bytesToHex(nameArray).replace(" ", ""),
            nameArray.size
        )

        //农具宽度
        val farmWidth = PrecisionAg.Instance().sysPara.mFarmImplement.ToolWidth * 100
        data += DataTool.getStrResult(
            DataTool.decimal2Hex(
                farmWidth.toLong(),
                4
            ), 2
        )
        //农具侧向偏移
        if (PrecisionAg.Instance().sysPara.mFarmImplement != null) {
            val DirectionOffset =
                PrecisionAg.Instance().sysPara.mFarmImplement.DirectionOffset * PrecisionAg.Instance().sysPara.mFarmImplement.direction * 100
            data += DataTool.getStrResult(
                DataTool.decimal2Hex(
                    DirectionOffset.toLong(),
                    4
                ), 2
            )
        }
        data += "0000" //农具前后偏移

        //作业面积
        val area = lineWork.Area * 100
        data += DataTool.getStrResult(
            DataTool.decimal2Hex(
                area.toLong(),
                8
            ), 4
        )
        //作业状态
        val isRunning = if (ConstantUtils.mCarRunning) "00" else "01"
        data += isRunning
        //result += "1100" //帧数据长度16
        result += DataTool.getStrResult(DataTool.decimal2Hex((data.length / 2).toLong(), 4), 2)
        result += data //帧数据
        result += "7f"
        return DataTool.getSendByte(result)
    }

    private fun response(msg: ByteArray) {
        if (msg.size > 0) {
            when (msg[2].toInt()) {
                0x02 -> {
                    DataTool.LogHuBei("02---断开连接" + CommFunction.bytesToHex(msg).replace(" ", ""))
                    //Log.e("qwq", "02---断开连接")
                }

                0x40 -> {
                    DataTool.LogHuBei("40---登录成功并上传图片" + String(msg) + CommFunction.bytesToHex(msg).replace(" ", ""))
                    Log.e("qwq", "40---登录成功" + String(msg))
                    uploadDbWorkCache()
                    sendPicThread()
                }

                0x58 -> {
                    //<终端 ID><数据发送/接收数量>
                    DataTool.LogHuBei("58---SD_FINISH: " + CommFunction.bytesToHex(msg).replace(" ", ""))
                    Log.e("qwq", "58---SD_FINISH: " + CommFunction.bytesToHex(msg).replace(" ", ""))
                    if(!isPicResponse && isSendPic){
                        picStopCount++
                        if(picStopCount>20){
                            sendPicThread()
                        }
                    }
                }

                0x59 -> {
                    //<终端 ID><接口帧号><数据发送/接收数量>
                    DataTool.LogHuBei("59---SD_AUTO_FINISH: " + CommFunction.bytesToHex(msg).replace(" ", ""))
                    Log.e("qwq", "59---SD_AUTO_FINISH: " + CommFunction.bytesToHex(msg).replace(" ", ""))
                    if(!isPicResponse && isSendPic){
                        picStopCount++
                        if(picStopCount>20){
                            sendPicThread()
                        }
                    }
                }

                0x65 -> {
                    Log.e("qwq", "65---图片请求")
                }

                0x66 -> {
                    DataTool.LogHuBei("0x66   图片数据确认: " + CommFunction.bytesToHex(msg).replace(" ", ""))
                    Log.e("qwq","66---图片数据确认: " + CommFunction.bytesToHex(msg).replace(" ", ""))
                    isPicResponse = true
                    picStopCount = 0
                    if (sendIndex == mByteList.size) {
                        sendByteData(DataTool.getSendByte(sendPicEndData()))
                        isSendPic = false
                    } else {
                        if((msg[12]==picNumber[12] && msg[13]==picNumber[13]) && isSendPic){
                            sendBitPic()
                        }
                    }
                }

                0x67 -> {
                    DataTool.LogHuBei("0x67   图片文件确认: " + CommFunction.bytesToHex(msg).replace(" ", ""))
                    Log.e("qwq", "67---图片文件确认")
                }

                else -> {
                    Log.e("qwq", "else---其他返回")
                }
            }
        }
    }

    fun saveUnUploadTrack(uploadTrackData: String?) {
        try {
            val jsonObject = JSONObject()
            jsonObject.put("type", 5)
            jsonObject.put("data", uploadTrackData)
            val tracksCache = TracksCache()
            tracksCache.isUp = false
            tracksCache.tracksInfo = jsonObject.toString()
            DataBaseOperator.Instance().iDataBaseHelper.insertTracksCache(tracksCache)
        } catch (e: Exception) {
        }
    }

    fun uploadDbWorkCache() {
        if (!RxNetTool.isNetworkAvailable(Application.getInstance().applicationContext)) {
            return
        }
        Application.getInstance().cachedThreadpool.execute {
            val tracksCacheAll = DataBaseOperator.Instance().iDataBaseHelper.findTracksCacheAll()
            val dataNumber = tracksCacheAll.size
            for (mCache in tracksCacheAll) {
                if (!mCache.isUp) {
                    val jsonObject = JSONObject(mCache.tracksInfo)
                    val type = jsonObject.getInt("type")
                    if (type == 5) {
                        if (sock?.isConnected == true) {
                            Log.e("qwq", "jsonObject:" + jsonObject.get("data").toString())
                            val b = DataTool.getSendByte(jsonObject.get("data").toString())
                            b[b.size-2] = 0xF0.toByte()
                            sendByteData(b)
                            mCache.isUp = true
                            //sendByteData(CommFunction.hexStrToByteArray(mCache.tracksInfo))
                            DataBaseOperator.Instance().iDataBaseHelper.updateTracksCacheEntry(mCache)
                        }
                    }
                }
                Thread.sleep(1000)
            }
        }
    }
}