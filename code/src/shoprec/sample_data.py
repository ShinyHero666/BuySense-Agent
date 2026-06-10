from __future__ import annotations

from datetime import datetime, timedelta, timezone

from .models import Product, UserProfile


def _days_ago(days: int, reference_time: datetime) -> datetime:
    return reference_time - timedelta(days=days)


def demo_products(reference_time: datetime | None = None) -> list[Product]:
    now = reference_time or datetime.now(timezone.utc)
    days_ago = lambda days: _days_ago(days, now)
    return [
        Product("p001", "iPhone 15 Pro 256G 国行", "手机", "Apple", 6299, "95新", "上海", "s01", 1, days_ago(2), ("iphone", "旗舰", "5g"), 0.95, 0.12, 0.025, service_mode="platform_inspected", inspection_grade="A", battery_health=95),
        Product("p002", "iPhone 13 128G 电池健康 90%", "手机", "Apple", 2799, "9成新", "北京", "s02", 1, days_ago(8), ("iphone", "5g", "高性价比"), 0.88, 0.10, 0.030, service_mode="recycle_inventory", inspection_grade="B", battery_health=90, inspection_findings=("边框轻微划痕",)),
        Product("p003", "华为 Mate 60 Pro 12+512G", "手机", "Huawei", 4899, "95新", "深圳", "s03", 1, days_ago(5), ("华为", "旗舰", "鸿蒙"), 0.92, 0.11, 0.024, service_mode="platform_inspected", inspection_grade="A", battery_health=97),
        Product("p004", "小米 14 12+256G 黑色", "手机", "Xiaomi", 2999, "99新", "杭州", "s04", 1, days_ago(1), ("小米", "安卓", "旗舰"), 0.87, 0.08, 0.022, service_mode="store_inventory", inspection_grade="A", battery_health=100),
        Product("p005", "MacBook Air M2 16G 512G", "电脑", "Apple", 6699, "95新", "上海", "s05", 1, days_ago(6), ("macbook", "轻薄本", "办公"), 0.94, 0.09, 0.018, service_mode="consignment", inspection_grade="A"),
        Product("p006", "ThinkPad X1 Carbon 2023", "电脑", "Lenovo", 5899, "9成新", "北京", "s06", 1, days_ago(18), ("thinkpad", "商务", "轻薄本"), 0.89, 0.07, 0.020, service_mode="recycle_inventory", inspection_grade="B", inspection_findings=("A面轻微使用痕迹",)),
        Product("p007", "索尼 WH-1000XM5 降噪耳机", "数码配件", "Sony", 1699, "95新", "上海", "s07", 1, days_ago(3), ("耳机", "降噪", "蓝牙"), 0.91, 0.11, 0.028, service_mode="consignment", inspection_grade="B"),
        Product("p008", "AirPods Pro 二代 USB-C", "数码配件", "Apple", 1199, "99新", "苏州", "s01", 1, days_ago(4), ("耳机", "降噪", "苹果生态"), 0.90, 0.13, 0.032, service_mode="platform_inspected", inspection_grade="A"),
        Product("p009", "佳能 EOS R6 全画幅微单", "摄影", "Canon", 8999, "9成新", "广州", "s08", 1, days_ago(25), ("相机", "全画幅", "微单"), 0.93, 0.06, 0.015),
        Product("p010", "索尼 A7M4 单机身", "摄影", "Sony", 11299, "95新", "深圳", "s09", 1, days_ago(11), ("相机", "全画幅", "微单"), 0.96, 0.07, 0.014),
        Product("p011", "Nike Dunk Low 熊猫配色 42码", "运动户外", "Nike", 499, "9成新", "成都", "s10", 1, days_ago(7), ("球鞋", "板鞋", "42码"), 0.82, 0.10, 0.040),
        Product("p012", "通勤双肩背包 15寸电脑可用", "箱包", "Generic", 129, "95新", "杭州", "s11", 1, days_ago(2), ("背包", "通勤", "电脑包"), 0.75, 0.09, 0.050),
        Product("p013", "捷安特 Escape 1 平把公路车", "运动户外", "Giant", 1899, "9成新", "上海", "s12", 1, days_ago(14), ("自行车", "通勤", "公路车"), 0.86, 0.05, 0.020),
        Product("p014", "深入理解计算机系统 第3版", "图书", "Pearson", 68, "8成新", "南京", "s13", 1, days_ago(40), ("书", "计算机", "csapp"), 0.80, 0.06, 0.045),
        Product("p015", "Python 数据分析 第3版", "图书", "OReilly", 72, "95新", "北京", "s14", 1, days_ago(9), ("书", "python", "数据分析"), 0.84, 0.07, 0.048),
        Product("p016", "iPhone 12 64G 仅作零件机", "手机", "Apple", 499, "故障", "上海", "s15", 0, days_ago(80), ("iphone", "零件"), 0.30, 0.02, 0.002, service_mode="recycle_inventory", inspection_grade="D", warranty_days=0, return_window_days=0, battery_health=62, inspection_findings=("无法开机",)),
        Product("p017", "iPhone 11 128G 主板维修记录", "手机", "Apple", 1299, "8成新", "广州", "s16", 1, days_ago(30), ("iphone", "维修机"), 0.55, 0.03, 0.005, service_mode="recycle_inventory", inspection_status="rejected", inspection_grade="D", warranty_days=0, return_window_days=0, battery_health=76, inspection_findings=("主板维修", "非原装屏幕")),
    ]


def demo_users() -> dict[str, UserProfile]:
    return {
        "u001": UserProfile(
            user_id="u001",
            city="上海",
            category_interests={"手机": 0.95, "数码配件": 0.78, "电脑": 0.55},
            recent_clicks=["p002", "p003", "p007"],
            purchased={"p002"},
            disliked_products={"p011"},
        ),
        "u002": UserProfile(
            user_id="u002",
            city="深圳",
            category_interests={"摄影": 0.92, "电脑": 0.40},
            recent_clicks=["p009", "p010"],
        ),
        "guest": UserProfile(user_id="guest", city="", category_interests={}),
    }
