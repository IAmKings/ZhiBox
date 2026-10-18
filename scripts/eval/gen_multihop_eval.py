#!/usr/bin/env python3
"""生成 B10 中文多跳评测集 + 结构化 KB（受控合成语料，供 instrumentation 真机评测）。

20 条链 × 4 chunk = 80 chunk，20 个文档；每条链：
  C1 人物 P 在组织 O 任职 → C2 O 位于城市 L → C3 L 以特产 F 闻名 → C4 F 由公司 F 生产
120 条查询：每链 2 跳×2、3 跳×2、4 跳×2。expected evidence 为 chunk 序号（1-based 插入顺序）。
输出：app/src/androidTest/assets/b10/kb.json 与 queries.json。
"""
import json, pathlib

P = ["张三","李四","王五","赵敏","刘洋","陈明","周航","孙悦","王丽","张伟",
     "李强","赵磊","钱峰","孙敏","周杰","吴迪","郑爽","冯涛","陈静","褚岩"]
O = ["星河科技","云帆数据","华信医疗","远航教育","青山能源","蓝海物流","启明电子","天工制造",
     "绿洲食品","恒达金融","光远传媒","海纳咨询","锐思软件","博雅文化","中科材料","东篱农业",
     "新元电气","瑞康药业","天成建筑","云图智能"]
L = ["深圳市","杭州市","上海市","北京市","南京市","广州市","成都市","武汉市",
     "西安市","苏州市","重庆市","天津市","青岛市","长沙市","厦门市","合肥市",
     "宁波市","无锡市","济南市","福州市"]
F = ["智能音箱","数据平台","康复设备","在线课程","储能电池","冷链系统","显示屏","精密零件",
     "有机茶叶","理财产品","纪录片","管理咨询","数据库","文创产品","新型材料","有机大米",
     "变压器","仿制药","装配式建筑","图像识别软件"]
C = ["星云电子","云帆智造","仁和医疗","知远教育","青山储能","蓝海物流","启明显示","天工精密",
     "绿洲茶业","恒达财富","光远影业","海纳咨询","锐思数据","博雅文创","中科新材","东篱农业",
     "新元电气","瑞康制药","天成建设","云图科技"]
N = ["中国","中国","中国","中国","中国","中国","中国","中国","中国","中国",
     "中国","中国","中国","中国","中国","中国","中国","中国","中国","中国"]
ROLE = ["技术总监","数据架构师","心内科主任","课程主管","储能工程师","物流经理","显示技术专家","工艺工程师",
        "产品经理","投资顾问","纪录片导演","咨询顾问","数据库管理员","设计师","材料研究员","农业专家",
        "电气工程师","药物研发主管","建筑设计师","算法工程师"]
PROJ = ["智能语音项目","数据治理平台","心脏康复项目","AI 课程体系","储能安全项目","冷链优化项目","新型显示项目","精密加工项目",
        "茶叶品牌项目","财富管理平台","文化纪录项目","战略咨询项目","数据库迁移项目","文创设计项目","新材料研发项目","有机农业项目",
        "智能电网项目","新药临床项目","绿色建筑项目","图像识别项目"]
YEAR = list(range(2001, 2021))

chains = []
for i in range(20):
    p, o, l, f, c, n, role, proj, year = P[i], O[i], L[i], F[i], C[i], N[i], ROLE[i], PROJ[i], YEAR[i]
    chunks = [
        {
            "content": f"{p}在{o}担任{role}，负责{proj}。",
            "event": {"title": f"{p}在{o}任职", "summary": f"{p}在{o}担任{role}", "content": f"{p}在{o}担任{role}，负责{proj}。"},
            "entities": [
                {"type": "person", "name": p, "description": f"{o}的{role}"},
                {"type": "organization", "name": o, "description": "企业"},
                {"type": "action", "name": role, "description": "职务/动作"},
                {"type": "subject", "name": proj, "description": "项目"},
            ],
        },
        {
            "content": f"{o}总部位于{l}，成立于{year}年。",
            "event": {"title": f"{o}总部与成立时间", "summary": f"{o}位于{l}", "content": f"{o}总部位于{l}，成立于{year}年。"},
            "entities": [
                {"type": "organization", "name": o, "description": "企业"},
                {"type": "location", "name": l, "description": "城市"},
                {"type": "time", "name": f"{year}年", "description": "成立年份"},
            ],
        },
        {
            "content": f"{l}以特产{f}闻名，{f}由{c}生产。",
            "event": {"title": f"{l}的特产与生产企业", "summary": f"{l}以{f}闻名", "content": f"{l}以特产{f}闻名，{f}由{c}生产。"},
            "entities": [
                {"type": "location", "name": l, "description": "城市"},
                {"type": "product", "name": f, "description": "特产/产品"},
                {"type": "organization", "name": c, "description": "生产企业"},
            ],
        },
        {
            "content": f"{c}位于{n}，是{f}的主要生产商。",
            "event": {"title": f"{c}与{f}", "summary": f"{c}位于{n}", "content": f"{c}位于{n}，是{f}的主要生产商。"},
            "entities": [
                {"type": "organization", "name": c, "description": "生产企业"},
                {"type": "location", "name": n, "description": "国家"},
                {"type": "product", "name": f, "description": "产品"},
            ],
        },
    ]
    chains.append({"person": p, "org": o, "city": l, "food": f, "company": c, "country": n, "chunks": chunks})

kb_docs = []
chunks_out = []
queries = []
qid = 1
for ci, chain in enumerate(chains):
    doc_id = f"doc-b10-{ci}"
    kb_docs.append({"id": doc_id, "title": f"{chain['person']}与{chain['org']}"})
    base = ci * 4  # 0-based index; ids = base+1..base+4
    for j, ch in enumerate(chain["chunks"]):
        chunks_out.append({
            "id": base + j + 1,
            "documentId": doc_id,
            "ordinal": j,
            "content": ch["content"],
            "event": ch["event"],
            "entities": ch["entities"],
        })
    ev = [base + 1, base + 2]
    ev3 = [base + 1, base + 2, base + 3]
    ev4 = [base + 1, base + 2, base + 3, base + 4]
    p, o, l, f, c, n = chain["person"], chain["org"], chain["city"], chain["food"], chain["company"], chain["country"]
    qs = [
        (f"{p}所在组织总部位于哪座城市？", ev, 2),
        (f"{p}任职的机构坐落在哪座城市？", ev, 2),
        (f"{p}所在组织总部所在城市以什么特产闻名？", ev3, 3),
        (f"{p}任职城市最著名的特产是什么？", ev3, 3),
        (f"{p}所在组织总部所在城市的特产由哪家公司生产？", ev4, 4),
        (f"{p}任职城市特产的生产公司位于哪个国家？", ev4, 4),
    ]
    for q, evidence, hops in qs:
        queries.append({"id": f"q{qid:03d}", "query": q, "evidence": evidence, "hops": hops})
        qid += 1

out = pathlib.Path("app/src/androidTest/assets/b10")
out.mkdir(parents=True, exist_ok=True)
(out / "kb.json").write_text(json.dumps({"documents": kb_docs, "chunks": chunks_out}, ensure_ascii=False, indent=2), encoding="utf-8")
(out / "queries.json").write_text(json.dumps({"queries": queries}, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"wrote {len(chunks_out)} chunks, {len(queries)} queries")
