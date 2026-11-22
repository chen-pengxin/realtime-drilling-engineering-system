"""SY/T 5435-2012 Appendix A, transcribed from the provided original.

These are blank source templates. They must never create fictitious well
stations or turn unknown target geometry into zero-valued measurements.
"""


def appendix_templates():
    labels = [
        ('A.1', '井基础数据', 50,
         ['大地坐标系', '指北方向', '深度基准', '海拔高度（m）', '纵坐标X（m）', '横坐标Y（m）',
          '子午线收敛角（°）', '磁偏角（°）', '补心高度（m）', '口袋长度（m）']),
        ('A.2', '靶区数据', 50,
         ['靶点名称', '海拔高度（m）', '纵坐标X（m）', '横坐标Y（m）', '垂深（m）', '水平位移（m）',
          '平移方位（°）', '形状', '半径（m）', '高度（m）', '宽度（m）']),
        ('A.3', '设计轨道节点数据', 50, None),
        ('A.4', '设计轨道分点数据', 51, None),
        ('A.5', '实钻轨迹测斜数据', 51, None),
        ('A.6', '实钻轨迹入靶数据', 51,
         ['靶点名称', '井深（m）', '井斜角（°）', '方位角（°）', '垂深（m）', '水平位移（m）',
          '平移方位（°）', '靶心距（m）', '纵偏（m）', '横偏（m）', '备注']),
    ]
    trajectory = ['井深（m）', '井斜角（°）', '方位角（°）', '垂深（m）', '北坐标（m）',
                  '东坐标（m）', '水平位移（m）', '平移方位（°）', '视平长（m）', '井眼曲率（°/30m）', '备注']
    notes = {
        'A.2': ['靶区几何尺寸应按靶区形状填写。'],
        'A.3': ['节点应包括井口、造斜点和靶点，并在备注栏注明。'],
        'A.4': ['输出井段内的节点应包括在内，并在备注栏注明。'],
        'A.5': ['计算方法应注明；插值点如有，应在备注栏注明。'],
        'A.6': ['纵向、横向偏移距的名称随靶面方向确定；水平靶可使用北向、东向偏移距。', '中靶或脱靶情况应在备注栏注明。'],
    }
    result = []
    for code, title, page, fields in labels:
        fields = fields or [v.replace('视平长','水平长度') if code in ('A.3','A.4') else v for v in trajectory]
        rows, merges = [fields], []
        if code == 'A.1':
            rows.insert(0, ['参考系统', '', '', '井口坐标', '', '', '相关数据', '', '', ''])
            merges = [dict(row=0, column=c, rowspan=1, colspan=n) for c, n in ((0, 3), (3, 3), (6, 4))]
        elif code == 'A.2':
            rows.insert(0, ['靶点名称', '靶点坐标', '', '', '', '', '', '靶区几何', '', '', ''])
            rows[1][0] = ''
            merges = [dict(row=0, column=0, rowspan=2, colspan=1), dict(row=0, column=1, rowspan=1, colspan=6), dict(row=0, column=7, rowspan=1, colspan=4)]
        header_rows = len(rows)
        rows.append([''] * len(fields))
        result.append(dict(locator='standard:' + code, type='TABLE', title=code + ' ' + title,
                           semantic_type='STANDARD_TEMPLATE', template=True, page=page, pages=[page],
                           header_rows=header_rows, rows=rows, merges=merges, notes=notes.get(code, []),
                           source_standard='SY/T 5435-2012', source_language='zh', records=[]))
    return result
