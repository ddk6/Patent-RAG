from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
from xml.sax.saxutils import escape
from PIL import Image
import shutil


OUT_DIR = Path(r"D:\code\KnowLink\generated_reports")
OUT = OUT_DIR / "chapter6_experiment_report.docx"
IMG_DIR = Path(r"D:\Lab\result")
WORK = Path(r"D:\code\KnowLink\_docx_build_knowlink")


IMAGE_BY_SIZE = {
    "inner_product_wave": 135723,
    "inner_product_run": 155247,
    "mv_code": 128210,
    "mv_case1_data": 19325,
    "mv_case1_run": 47148,
    "mv_case1_wave": 238684,
    "mv_case2_data": 29838,
    "mv_case2_run": 54204,
    "mv_case2_wave": 234949,
    "mv_case3_data": 32656,
    "mv_case3_run": 59131,
    "mv_case3_wave": 235620,
    "mv_case4_data1": 26075,
    "mv_case4_data2": 10923,
    "mv_case4_run": 65444,
    "mv_case4_wave": 230519,
    "mm_code": 126582,
    "mm_run": 131965,
    "mm_wave": 243690,
}

NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
NS_WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
NS_PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"


def setup_workspace():
    OUT_DIR.mkdir(exist_ok=True)
    if WORK.exists():
        shutil.rmtree(WORK)
    (WORK / "_rels").mkdir(parents=True)
    (WORK / "docProps").mkdir()
    (WORK / "word" / "_rels").mkdir(parents=True)
    (WORK / "word" / "media").mkdir(parents=True)


def collect_images():
    size_to_file = {p.stat().st_size: p for p in IMG_DIR.glob("*.png")}
    rel_entries = []
    img_info = {}
    for idx, (key, size) in enumerate(IMAGE_BY_SIZE.items(), start=1):
        src = size_to_file.get(size)
        if not src:
            print(f"missing image for {key}, size={size}")
            continue
        media_name = f"image{idx}{src.suffix.lower()}"
        shutil.copyfile(src, WORK / "word" / "media" / media_name)
        rid = f"rId{idx}"
        rel_entries.append((rid, media_name))
        with Image.open(src) as im:
            img_info[key] = {
                "rid": rid,
                "name": src.name,
                "w": im.width,
                "h": im.height,
            }
    return rel_entries, img_info


body = []


def p(text="", style=None, align=None, bold=False):
    props = ""
    if style:
        props += f'<w:pStyle w:val="{style}"/>'
    if align:
        props += f'<w:jc w:val="{align}"/>'
    ppr = f"<w:pPr>{props}</w:pPr>" if props else ""
    rpr = "<w:rPr><w:b/></w:rPr>" if bold else ""
    if text == "":
        body.append("<w:p/>")
    else:
        body.append(
            f'<w:p>{ppr}<w:r>{rpr}<w:t xml:space="preserve">{escape(text)}</w:t></w:r></w:p>'
        )


def heading(text, level=1):
    p(text, style="Heading1" if level == 1 else "Heading2")


def code_block(text):
    for line in text.strip("\n").split("\n"):
        body.append(
            '<w:p><w:pPr><w:pStyle w:val="Code"/></w:pPr><w:r><w:rPr>'
            '<w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/><w:sz w:val="19"/>'
            f'</w:rPr><w:t xml:space="preserve">{escape(line)}</w:t></w:r></w:p>'
        )


def bullet(text):
    body.append(
        '<w:p><w:pPr><w:pStyle w:val="ListBullet"/></w:pPr><w:r>'
        f'<w:t xml:space="preserve">{escape("• " + text)}</w:t></w:r></w:p>'
    )


def table(rows):
    body.append(
        '<w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/>'
        '<w:tblW w:w="0" w:type="auto"/>'
        '<w:tblLook w:val="04A0" w:firstRow="1" w:lastRow="0" '
        'w:firstColumn="1" w:lastColumn="0" w:noHBand="0" w:noVBand="1"/>'
        "</w:tblPr>"
    )
    for row in rows:
        body.append("<w:tr>")
        for cell in row:
            body.append(
                '<w:tc><w:tcPr><w:tcW w:w="2400" w:type="dxa"/></w:tcPr>'
                f'<w:p><w:r><w:t xml:space="preserve">{escape(str(cell))}</w:t></w:r></w:p></w:tc>'
            )
        body.append("</w:tr>")
    body.append("</w:tbl>")


def add_image(img_info, key, caption, max_width_in=6.2):
    info = img_info.get(key)
    if not info:
        p(f"（缺少图片：{caption}）")
        return
    w_px, h_px = info["w"], info["h"]
    width_in = min(max_width_in, max(2.8, w_px / 190.0))
    height_in = width_in * h_px / w_px
    if height_in > 8.4:
        height_in = 8.4
        width_in = height_in * w_px / h_px
    cx = int(width_in * 914400)
    cy = int(height_in * 914400)
    rid = info["rid"]
    name = escape(info["name"])
    docpr_id = len(body) + 1
    body.append(
        f'<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:drawing>'
        f'<wp:inline distT="0" distB="0" distL="0" distR="0">'
        f'<wp:extent cx="{cx}" cy="{cy}"/><wp:effectExtent l="0" t="0" r="0" b="0"/>'
        f'<wp:docPr id="{docpr_id}" name="{name}"/>'
        f'<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a="{NS_A}" noChangeAspect="1"/></wp:cNvGraphicFramePr>'
        f'<a:graphic xmlns:a="{NS_A}"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">'
        f'<pic:pic xmlns:pic="{NS_PIC}"><pic:nvPicPr><pic:cNvPr id="0" name="{name}"/><pic:cNvPicPr/></pic:nvPicPr>'
        f'<pic:blipFill><a:blip r:embed="{rid}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>'
        f'<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="{cx}" cy="{cy}"/></a:xfrm>'
        f'<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>'
        f'</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>'
    )
    p(caption, align="center")


def build_content(img_info):
    p("第六章实验报告", style="Title", align="center")
    p("实验题目：基于 Verilog 的内积、矩阵乘向量与矩阵乘矩阵运算器设计与仿真", align="center")
    p("姓名：__________    学号：__________    班级：__________    日期：__________", align="center")
    p()

    heading("一、实验目的")
    bullet("理解深度学习处理器中内积、矩阵乘向量、矩阵乘矩阵三类基础运算的硬件实现方式。")
    bullet("掌握使用 Verilog 通过模块例化构建层次化计算单元的方法。")
    bullet("掌握基于 Verilator 的编译、运行和 GTKWave 波形查看流程。")
    bullet("通过多组不同维度输入数据进行对照实验，验证矩阵乘向量模块在不同规模下的正确性。")

    heading("二、实验环境")
    table(
        [
            ["项目", "内容"],
            ["操作环境", "Windows + VSCode Remote WSL / Ubuntu 22.04"],
            ["仿真工具", "Verilator、GTKWave"],
            ["源码目录", "/home/dlp_ex_student/src"],
            ["仿真目录", "/home/dlp_ex_student/sim"],
            ["数据目录", "/home/dlp_ex_student/data"],
        ]
    )

    heading("三、实验原理")
    p(
        "本实验围绕 DLP 中常见的乘加计算展开。内积模块完成 4 个 16 位有符号输入与 4 个 16 位有符号权重的乘加，输出 32 位有符号结果。矩阵乘向量模块通过并行例化 4 个内积模块实现，每个内积模块负责权重矩阵的一行与输入向量相乘。矩阵乘矩阵模块进一步例化 4 个矩阵乘向量模块，每个模块处理激活矩阵的一行，从而得到 4x4 输出矩阵。"
    )
    p("测试平台通过 config 指定有效维度。矩阵乘向量实验使用 1、2、3、4 维数据进行对照验证；矩阵乘矩阵实验使用 4x4 单位矩阵进行功能验证。")

    heading("四、模块设计与代码补全")
    heading("4.1 内积运算器 inner_product_4x16", 2)
    p("内积模块完成输入寄存、权重寄存和乘加运算。仿真中输入向量为 1、2、3、4，权重为 1、0、0、0，预期输出为 1。")
    add_image(img_info, "inner_product_run", "图1 inner_product_4x16 运行结果截图")
    add_image(img_info, "inner_product_wave", "图2 inner_product_4x16 GTKWave 仿真波形截图")

    heading("4.2 矩阵乘向量 matrix_vector_mult_4x4x16", 2)
    p("该模块补全的核心是例化 4 个 inner_product_4x16 单元。四个单元共用同一个 activations 输入向量，分别连接 weights[0] 到 weights[3]，输出分别连接 results[0] 到 results[3]。")
    code_block(
        """inner_product_4x16 ipu0 (... .activations(activations), .weights(weights[0]), .result(results[0]));
inner_product_4x16 ipu1 (... .activations(activations), .weights(weights[1]), .result(results[1]));
inner_product_4x16 ipu2 (... .activations(activations), .weights(weights[2]), .result(results[2]));
inner_product_4x16 ipu3 (... .activations(activations), .weights(weights[3]), .result(results[3]));"""
    )
    add_image(img_info, "mv_code", "图3 matrix_vector_mult_4x4x16 补全代码截图")

    heading("4.3 矩阵乘矩阵 matrix_mult_4x4x4x16", 2)
    p("矩阵乘矩阵模块补全时例化 4 个 matrix_vector_mult_4x4x16 单元。每个单元连接激活矩阵的一行 activations[i]，共享同一个 weights 矩阵，并将输出连接到 results[i]。")
    code_block(
        """matrix_vector_mult_4x4x16 mxv0 (... .activations(activations[0]), .weights(weights), .results(results[0]));
matrix_vector_mult_4x4x16 mxv1 (... .activations(activations[1]), .weights(weights), .results(results[1]));
matrix_vector_mult_4x4x16 mxv2 (... .activations(activations[2]), .weights(weights), .results(results[2]));
matrix_vector_mult_4x4x16 mxv3 (... .activations(activations[3]), .weights(weights), .results(results[3]));"""
    )
    add_image(img_info, "mm_code", "图4 matrix_mult_4x4x4x16 补全代码截图")

    heading("五、实验步骤")
    bullet("在 VSCode 中连接 WSL，并进入 /home/dlp_ex_student/sim 目录。")
    bullet("分别编译 inner_product_4x16、matrix_vector_mult_4x4x16、matrix_mult_4x4x4x16 三个模块。")
    bullet("运行 obj_dir 下生成的可执行文件，观察终端输出的实际结果、预期结果与通过情况。")
    bullet("使用 GTKWave 打开对应的 vcd 文件，观察输入、寄存器、enable、reset、result 等信号变化。")
    bullet("针对 matrix_vector_mult_4x4x16 准备四组 1/2/3/4 维对照数据，分别运行并记录结果。")

    heading("六、实验数据与结果")
    heading("6.1 内积运算器实验结果", 2)
    p("内积实验中，输入向量为 [1, 2, 3, 4]，权重向量为 [1, 0, 0, 0]，理论输出为 1。运行结果显示实际值为 1，预期值为 1，验证通过。")

    heading("6.2 矩阵乘向量四组对照实验", 2)
    table(
        [
            ["组别", "维度", "输入向量", "预期结果", "验证情况"],
            ["第一组", "1", "[3]", "[15]", "通过"],
            ["第二组", "2", "[1, 2]", "[11, 17]", "通过"],
            ["第三组", "3", "[1, -2, 3]", "[5, -3, 4]", "通过"],
            ["第四组", "4", "[7, 0, -3, 2]", "[12, -11, 9, 23]", "通过"],
        ]
    )

    heading("第一组：1 维对照", 2)
    p("第一组 config 为 1，neuron 为 3，weight 为 5，理论结果为 15。运行结果显示实际结果与预期结果一致。")
    add_image(img_info, "mv_case1_data", "图5 第一组 1 维 data 数据截图")
    add_image(img_info, "mv_case1_run", "图6 第一组 1 维运行结果截图")
    add_image(img_info, "mv_case1_wave", "图7 第一组 1 维仿真波形截图")

    heading("第二组：2 维对照", 2)
    p("第二组 config 为 2，输入向量为 [1, 2]，权重矩阵为 [[3,4],[5,6]]，计算结果为 [11,17]。终端输出中两个结果均通过验证。")
    add_image(img_info, "mv_case2_data", "图8 第二组 2 维 data 数据截图")
    add_image(img_info, "mv_case2_run", "图9 第二组 2 维运行结果截图")
    add_image(img_info, "mv_case2_wave", "图10 第二组 2 维仿真波形截图")

    heading("第三组：3 维对照", 2)
    p("第三组 config 为 3，输入向量为 [1, -2, 3]，包含负数输入，用于验证 signed 有符号运算。输出结果为 [5, -3, 4]，三项均通过。")
    add_image(img_info, "mv_case3_data", "图11 第三组 3 维 data 数据截图")
    add_image(img_info, "mv_case3_run", "图12 第三组 3 维运行结果截图")
    add_image(img_info, "mv_case3_wave", "图13 第三组 3 维仿真波形截图")

    heading("第四组：4 维对照", 2)
    p("第四组 config 为 4，输入向量为 [7, 0, -3, 2]，权重矩阵包含正数、负数与 0，输出结果为 [12, -11, 9, 23]。运行截图显示四项结果均通过。")
    add_image(img_info, "mv_case4_data1", "图14 第四组 4 维 data 数据截图之一")
    add_image(img_info, "mv_case4_data2", "图15 第四组 4 维 result 数据截图")
    add_image(img_info, "mv_case4_run", "图16 第四组 4 维运行结果截图")
    add_image(img_info, "mv_case4_wave", "图17 第四组 4 维仿真波形截图")

    heading("6.3 矩阵乘矩阵实验结果", 2)
    p("矩阵乘矩阵实验采用 4x4 单位矩阵作为激活矩阵和权重矩阵，理论输出仍为 4x4 单位矩阵。运行结果中 results[0][0]、results[1][1]、results[2][2]、results[3][3] 为 1，其余位置为 0，全部通过。")
    add_image(img_info, "mm_run", "图18 matrix_mult_4x4x4x16 运行结果截图")
    add_image(img_info, "mm_wave", "图19 matrix_mult_4x4x4x16 仿真波形截图")

    heading("七、结果分析")
    p("从内积实验可以看出，输入数据在 enable 有效后被寄存，随后 dot_product 和 result 产生正确输出，验证了基本乘加功能。矩阵乘向量实验中，四个内积单元并行工作，不同维度的对照数据均能得到正确结果，说明模块端口连接、二维数组索引以及有符号数处理均正确。矩阵乘矩阵实验通过复用矩阵乘向量模块完成二维输出矩阵计算，单位矩阵测试结果全部通过，说明层次化组合方式正确。")
    p("波形观察表明，reset 释放后输入数据稳定加载，enable 拉高期间运算单元完成数据寄存和乘加，result 在后续时钟周期稳定输出。该现象与测试平台等待若干时钟后捕获结果的设计一致。")

    heading("八、实验结论")
    p("本实验完成了 inner_product_4x16、matrix_vector_mult_4x4x16 和 matrix_mult_4x4x4x16 三个模块的功能验证。通过 Verilator 运行结果和 GTKWave 波形截图可以确认：内积运算器能够正确完成 16 位有符号乘加；矩阵乘向量模块能够在 1、2、3、4 维不同规模下输出正确结果；矩阵乘矩阵模块能够基于矩阵乘向量模块完成 4x4 矩阵输出。实验达到预期目标。")

    heading("九、问题与改进")
    bullet("测试平台中数据文件路径需要与 WSL 实际目录保持一致，可通过软链接或修改 C++ 测试代码解决。")
    bullet("matrix_vector_mult 的 config 为单个维度，matrix_mult 的 config 为三个维度，实验时需要避免混用数据文件。")
    bullet("当前模块规模固定为最大 4x4，后续可通过参数化 Verilog 设计进一步支持更灵活的矩阵维度。")


def write_docx(rel_entries):
    sect = (
        '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>'
        '<w:pgMar w:top="1440" w:right="1200" w:bottom="1440" w:left="1200" '
        'w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>'
    )
    document_xml = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        f'<w:document xmlns:w="{NS_W}" xmlns:r="{NS_R}" xmlns:a="{NS_A}" '
        f'xmlns:wp="{NS_WP}" xmlns:pic="{NS_PIC}"><w:body>{"".join(body)}{sect}</w:body></w:document>'
    )
    styles_xml = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="{NS_W}">
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:rPr><w:rFonts w:ascii="Times New Roman" w:eastAsia="宋体" w:hAnsi="Times New Roman"/><w:sz w:val="24"/></w:rPr><w:pPr><w:spacing w:line="360" w:lineRule="auto"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:rPr><w:rFonts w:ascii="Times New Roman" w:eastAsia="黑体"/><w:b/><w:sz w:val="36"/></w:rPr><w:pPr><w:jc w:val="center"/><w:spacing w:after="240"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:rPr><w:rFonts w:eastAsia="黑体"/><w:b/><w:sz w:val="30"/></w:rPr><w:pPr><w:spacing w:before="240" w:after="120"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:rPr><w:rFonts w:eastAsia="黑体"/><w:b/><w:sz w:val="26"/></w:rPr><w:pPr><w:spacing w:before="180" w:after="80"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="ListBullet"><w:name w:val="List Bullet"/><w:basedOn w:val="Normal"/><w:pPr><w:ind w:left="420" w:hanging="210"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="Code"><w:name w:val="Code"/><w:basedOn w:val="Normal"/><w:rPr><w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/><w:sz w:val="19"/></w:rPr><w:pPr><w:spacing w:line="240" w:lineRule="auto"/></w:pPr></w:style>
<w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/><w:tblPr><w:tblBorders><w:top w:val="single" w:sz="4"/><w:left w:val="single" w:sz="4"/><w:bottom w:val="single" w:sz="4"/><w:right w:val="single" w:sz="4"/><w:insideH w:val="single" w:sz="4"/><w:insideV w:val="single" w:sz="4"/></w:tblBorders></w:tblPr></w:style>
</w:styles>'''
    rels_xml = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>'''
    doc_rels = [
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">',
    ]
    for rid, media_name in rel_entries:
        doc_rels.append(
            f'<Relationship Id="{rid}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/{media_name}"/>'
        )
    doc_rels.append("</Relationships>")
    content_types_xml = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Default Extension="png" ContentType="image/png"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>'''
    core_xml = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>第六章实验报告</dc:title><dc:creator>Codex</dc:creator><cp:lastModifiedBy>Codex</cp:lastModifiedBy><dcterms:created xsi:type="dcterms:W3CDTF">2026-05-24T00:00:00Z</dcterms:created><dcterms:modified xsi:type="dcterms:W3CDTF">2026-05-24T00:00:00Z</dcterms:modified></cp:coreProperties>'''
    app_xml = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>Codex</Application></Properties>'''

    (WORK / "[Content_Types].xml").write_text(content_types_xml, encoding="utf-8")
    (WORK / "_rels" / ".rels").write_text(rels_xml, encoding="utf-8")
    (WORK / "word" / "document.xml").write_text(document_xml, encoding="utf-8")
    (WORK / "word" / "styles.xml").write_text(styles_xml, encoding="utf-8")
    (WORK / "word" / "_rels" / "document.xml.rels").write_text("\n".join(doc_rels), encoding="utf-8")
    (WORK / "docProps" / "core.xml").write_text(core_xml, encoding="utf-8")
    (WORK / "docProps" / "app.xml").write_text(app_xml, encoding="utf-8")

    if OUT.exists():
        OUT.unlink()
    with ZipFile(OUT, "w", ZIP_DEFLATED) as z:
        for file in WORK.rglob("*"):
            if file.is_file():
                z.write(file, file.relative_to(WORK).as_posix())


def main():
    setup_workspace()
    rel_entries, img_info = collect_images()
    build_content(img_info)
    write_docx(rel_entries)
    print(OUT)
    print(f"images embedded: {len(rel_entries)}")
    print(f"size: {OUT.stat().st_size}")


if __name__ == "__main__":
    main()
