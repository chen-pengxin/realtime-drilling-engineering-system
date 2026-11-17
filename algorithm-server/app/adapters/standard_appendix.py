"""SY/T 5435-2012 Appendix A, transcribed from the provided original.

These are blank source templates. They must never create fictitious well
stations or turn unknown target geometry into zero-valued measurements.
"""


def appendix_templates():
    labels = [
        ('A.1', '井基本数据', 50,
         ['大地坐标系', '指北方向', '深度基准', '海拔（m）', '纵坐标X（m）', '横坐标Y（m）',
          '子午线收敛角（°）', '磁偏角（°）', '转盘补心高程RKB（m）', '口袋长度（m）']),
        ('A.2', '靶区数据', 50,
         ['靶点名称', '海拔（m）', '纵坐标X（m）', '横坐标Y（m）', '垂深（m）', '闭合距（m）',
          '闭合方位（°）', '形状', '半径（m）', '高度（m）', '宽度（m）']),
        ('A.3', '设计轨道节点数据', 50, None),
        ('A.4', '设计轨道插值点数据', 51, None),
        ('A.5', '实钻轨迹测斜数据', 51, None),
        ('A.6', '实钻轨迹中靶数据', 51,
         ['靶点名称', '测深MD（m）', '井斜Inc（°）', '方位Azi（°）', '垂深TVD（m）', '闭合距（m）',
          '闭合方位（°）', '偏移距（m）', '纵向偏移距（m）', '横向偏移距（m）', '备注']),
    ]
    trajectory = ['测深MD（m）', '井斜Inc（°）', '方位Azi（°）', '垂深TVD（m）', '南北位移N/S（m）',
                  '东西位移E/W（m）', '闭合距（m）', '闭合方位（°）', '视平长（m）', '井眼曲率（°/30m）', '备注']
    notes = {
        'A.2': ['靶区几何尺寸应按靶区形状填写。'],
        'A.3': ['节点应包括井口、造斜点和靶点，并在备注栏注明。'],
        'A.4': ['输出井段内的节点应包括在内，并在备注栏注明。'],
        'A.5': ['计算方法应注明；插值点如有，应在备注栏注明。'],
        'A.6': ['纵向、横向偏移距的名称随靶面方向确定；水平靶可使用北向、东向偏移距。', '中靶或脱靶情况应在备注栏注明。'],
    }
    result = []
    for code, title, page, fields in labels:
        fields = fields or trajectory.copy()
        rows, merges = [fields], []
        if code == 'A.1':
            rows.insert(0, ['参考体系', '', '', '井口坐标', '', '', '相关参数', '', '', ''])
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
                           source_standard='SY/T 5435-2012', source_language='en', records=[]))
    return result
