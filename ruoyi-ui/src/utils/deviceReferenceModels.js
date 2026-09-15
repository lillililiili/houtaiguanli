// Manufacturer specifications and certificate facts; never used to fill live measurements.
export const deviceReferenceModels = [
  {
    "code": "t02",
    "name": "T02 型低空监视雷达",
    "models": [
      "T02",
      "T02型低空监视雷达"
    ],
    "source": "T02型低空监视雷达规格书.docx · 产品规格",
    "note": "此型号参数与另一份未注明型号的雷达规格书不同，请按设备铭牌选用。",
    "fields": [
      {
        "label": "工作频段 / 体制",
        "value": "Ku；方位机扫 + 俯仰 DBF"
      },
      {
        "label": "探测量程 / 最大高度 / 盲区",
        "value": "3.5 km / 600 m / 50 m"
      },
      {
        "label": "水平 / 俯仰范围",
        "value": "360° / −5°～60°"
      },
      {
        "label": "速度测量范围",
        "value": "1～100 m/s"
      },
      {
        "label": "小型无人机探测",
        "value": "RCS=0.01 m²（精灵4）：≥1.5 km"
      },
      {
        "label": "中型无人机 / 车辆、直升机",
        "value": "RCS=1 m² / 5 m²：≥3.5 km"
      },
      {
        "label": "测距 / 测速精度",
        "value": "≤5 m / ≤0.5 m/s（RMS）"
      },
      {
        "label": "测角精度",
        "value": "方位、俯仰均 ≤0.4°（RMS）"
      },
      {
        "label": "刷新率",
        "value": "标配 2 s（30 rpm）；3 s（20 rpm）；可升级 1 s（60 rpm）"
      },
      {
        "label": "同时跟踪",
        "value": "≥100 批"
      },
      {
        "label": "工作温度 / 湿度",
        "value": "−40～55℃ / ≤90%"
      },
      {
        "label": "防护",
        "value": "IP66，雷达主机 IP67"
      },
      {
        "label": "供电 / 功耗",
        "value": "DC18～28 V，配 AC220 V 转 DC24 V；≤120 W"
      },
      {
        "label": "通信",
        "value": "以太网，圆形航插"
      },
      {
        "label": "总重",
        "value": "主机 + 转台 ≤25 kg"
      },
      {
        "label": "主机",
        "value": "≤353×453×87 mm；≤9 kg"
      },
      {
        "label": "转台",
        "value": "≤412×Φ216 mm；≤14.2 kg"
      },
      {
        "label": "三脚架",
        "value": "≤940×230×230 mm；≤12 kg"
      },
      {
        "label": "随机清单",
        "value": "雷达主机、转台、三脚架、运输箱、电源适配器、通讯电缆、工具及螺钉、电子使用说明书"
      }
    ]
  },
  {
    "code": "radar_unspecified",
    "name": "低空监视雷达（资料未注明型号）",
    "models": [],
    "source": "低空监视雷达规格书.docx · 主要指标",
    "note": "型号未明确，不能自动视为 T02，也不能把该资料参数视为当前设备实测值。",
    "fields": [
      {
        "label": "工作频段 / 跳频",
        "value": "Ku；6 个跳频"
      },
      {
        "label": "体制",
        "value": "方位机械扫描、俯仰 DBF"
      },
      {
        "label": "俯仰 / 方位",
        "value": "0～45° / 360°"
      },
      {
        "label": "探测高度",
        "value": "≥500 m"
      },
      {
        "label": "探测距离",
        "value": "精灵4、翼展＞1 m 鸟类 ≥2 km；中大型固定翼 ≥3.5 km"
      },
      {
        "label": "测角 / 测距精度",
        "value": "方位 ≤0.4°；俯仰 ≤0.6°；距离 ≤5 m（RMS）"
      },
      {
        "label": "盲区",
        "value": "≤50 m"
      },
      {
        "label": "转速 / 跟踪容量",
        "value": "30 rpm（180°/s）；≥100 批"
      },
      {
        "label": "径向速度范围",
        "value": "1～100 m/s"
      },
      {
        "label": "功耗 / 供电",
        "value": "≤180 W；DC24 V，带交流适配器"
      },
      {
        "label": "接口",
        "value": "千兆以太网"
      },
      {
        "label": "温度 / 防护",
        "value": "−40～55℃ / IP66"
      },
      {
        "label": "重量 / 主机尺寸",
        "value": "≤18 kg / ≤450×380×75 mm（不含伺服）"
      }
    ]
  },
  {
    "code": "gd01",
    "name": "UAD-GD01 无线电干扰仪",
    "models": [
      "UAD-GD01"
    ],
    "source": "UAD-GD01无线电干扰仪.docx · V1.0（2023 年 11 月）",
    "note": "本资料为三通道设备；不能套用四通道控制器映射。额定功率、工作电流、温度范围等不是实时读数。",
    "fields": [
      {
        "label": "厂家",
        "value": "北京神州明达高科技有限公司"
      },
      {
        "label": "通道1 / 通道2 / 通道3",
        "value": "1550～1620 MHz / 2400～2485 MHz / 5725～5850 MHz"
      },
      {
        "label": "各通道标称输出功率",
        "value": "30 W / 30 W / 25 W"
      },
      {
        "label": "输出驻波",
        "value": "≤2.0"
      },
      {
        "label": "供电",
        "value": "AC220 V"
      },
      {
        "label": "各通道最大工作电流",
        "value": "2.6 A / 2.6 A / 2.3 A（28 V）"
      },
      {
        "label": "效率 / 干扰源",
        "value": "各通道 ≥40%；内置高速扫频源"
      },
      {
        "label": "天线 / 控制",
        "value": "定向平板天线；网口软件控制各路开关"
      },
      {
        "label": "干扰半径",
        "value": "1～5 km，依环境与机型"
      },
      {
        "label": "主机尺寸 / 重量",
        "value": "42×34×23 cm / 9.8 kg"
      },
      {
        "label": "天线频率",
        "value": "1560～1620 / 2400～2500 / 5700～5900 MHz"
      },
      {
        "label": "天线增益",
        "value": "8±1 / 10±1 / 13±1 dBi"
      },
      {
        "label": "水平波瓣",
        "value": "70±5° / 50±5° / 40±5°"
      },
      {
        "label": "垂直波瓣",
        "value": "65±6° / 40±5° / 35±5°"
      },
      {
        "label": "前后比 / 阻抗",
        "value": "＞24 dB / 50 Ω"
      },
      {
        "label": "极化",
        "value": "圆极化 / V / V"
      },
      {
        "label": "天线最大功率 / 接头",
        "value": "50 W / 3×SMA-K"
      },
      {
        "label": "防雷 / 外罩",
        "value": "直流接地 / ABS"
      },
      {
        "label": "天线尺寸 / 重量",
        "value": "225×225×25 mm / 1.2 kg"
      },
      {
        "label": "抗风 / 湿度 / 温度",
        "value": "36.9 m/s / ＜95% / −40～75℃"
      }
    ]
  },
  {
    "code": "integrated_fixed",
    "name": "固定式侦打一体（含 UADS-ZG12 识别单元）",
    "models": [],
    "source": "无人机反制侦打一体-固定式资料.doc",
    "note": "整机型号未明确；资料对干扰距离使用半径和直径两种口径，必须现场确认，不自动合并。",
    "fields": [
      {
        "label": "识别单元",
        "value": "UADS-ZG12；双通道低噪声射频前端"
      },
      {
        "label": "识别信号",
        "value": "LB、LB2、Ocusync、Ocusync2.0、WiFi、增强型 WiFi、FM"
      },
      {
        "label": "侦测频段",
        "value": "2400～2500 / 5150～5975 MHz"
      },
      {
        "label": "侦测距离（半径）",
        "value": "3 km（2 dBi / 精灵4Pro V2.0 / 50 m 高）；6 km（8 dBi / 100 m 高）"
      },
      {
        "label": "扫描周期",
        "value": "2.1～5.25 s，可设置"
      },
      {
        "label": "烧毁电平 / 射频接口",
        "value": "≥+31 dBm / 2×MMCX 母座"
      },
      {
        "label": "数据接口",
        "value": "UART 115.2 kbps/N/8/1，TTL3.3 V"
      },
      {
        "label": "识别单元供电 / 功耗",
        "value": "DC2.7～5.5 V / 5～15 V / 9～36 V 可选；＜4 W"
      },
      {
        "label": "识别单元温度",
        "value": "环境 −40～55℃；外壳 −40～85℃"
      },
      {
        "label": "干扰模式 / 天线",
        "value": "定频；全向360°高增益天线"
      },
      {
        "label": "干扰频段",
        "value": "0.9 G、1.5 G、2.4 G、5.8 G"
      },
      {
        "label": "干扰距离（表中直径）",
        "value": "空旷 ≤3 km、城市 ≤1 km，依机型及环境"
      },
      {
        "label": "各频段标称发射功率",
        "value": "2390～2510 MHz：200 W；5708～5872 MHz：100 W；1552～1632 MHz：20 W；900 MHz：40 W"
      },
      {
        "label": "整机温湿度",
        "value": "−25～55℃；80%±3"
      },
      {
        "label": "整机重量 / 功耗 / 尺寸",
        "value": "≤60 kg / 1500 W / ≤63×50×35 cm"
      }
    ]
  },
  {
    "code": "certificate_210r",
    "name": "UAD-GRF-210R 固定式核准证",
    "models": [
      "UAD-GRF-210R"
    ],
    "source": "1-29 无线电发射设备型号核准证 · 正文及附页",
    "note": "核准证附页的频率、带宽和功率栏为横线，未提供数值；不能从证书推导实时工作参数。",
    "fields": [
      {
        "label": "设备名称",
        "value": "无人驾驶航空器无线电反制设备"
      },
      {
        "label": "证书记载型号",
        "value": "UAD-GRF-210R"
      },
      {
        "label": "证书编号",
        "value": "2025-8666"
      },
      {
        "label": "核准代码",
        "value": "25Z11MC8G800"
      },
      {
        "label": "发证日期 / 有效期",
        "value": "2025-05-16 / 证书记载三年"
      },
      {
        "label": "厂家",
        "value": "北京神州明达高科技有限公司"
      },
      {
        "label": "安装形态",
        "value": "固定式"
      },
      {
        "label": "生产地址",
        "value": "北京市昌平区北七家镇王府街10号院"
      },
      {
        "label": "是否代工",
        "value": "证书附页记载：是"
      }
    ]
  },
  {
    "code": "certificate_578d",
    "name": "UAD-GRB-578D 便携式核准证（文件名为 GRF）",
    "models": [
      "UAD-GRB-578D"
    ],
    "source": "1-31 无线电发射设备型号核准证 · 正文及附页",
    "note": "文件名写 UAD-GRF-578D，证书正文与附页均写 UAD-GRB-578D。待铭牌确认，未将两者自动视为同一型号。",
    "fields": [
      {
        "label": "设备名称",
        "value": "无人驾驶航空器无线电反制设备"
      },
      {
        "label": "证书记载型号",
        "value": "UAD-GRB-578D"
      },
      {
        "label": "证书编号",
        "value": "2025-8646"
      },
      {
        "label": "核准代码",
        "value": "25Z11MC8B267"
      },
      {
        "label": "发证日期 / 有效期",
        "value": "2025-05-16 / 证书记载三年"
      },
      {
        "label": "厂家",
        "value": "北京神州明达高科技有限公司"
      },
      {
        "label": "安装形态",
        "value": "便携式"
      },
      {
        "label": "频率 / 带宽 / 功率",
        "value": "附页为横线，未提供数值"
      },
      {
        "label": "生产地址",
        "value": "北京市昌平区北七家镇王府街10号院"
      },
      {
        "label": "是否代工",
        "value": "证书附页记载：是"
      }
    ]
  }
];
