# WITSML 接入说明

系统的 WITSML 接入位于 `algorithm-server/app/adapters/witsml_pds.py`、`survey_io.py` 和 `connector_pull.py`。

适配范围：

- WITSML 1.4.1/1.4.1.1 与 2.0 的轨迹 XML；
- `trajectory`、`trajectoryStation`、`well`、`wellbore` 等对象的识别；
- MD、井斜、方位、TVD、North、East 及单位转换；
- 多个 trajectory 对象和命名空间；
- XML 外部实体拒绝、大小限制和版本校验；
- 文件导入、WITSML 连接器读取、轨迹版本生成和 WITSML 原格式导出。

系统采用与 PDS WITSMLstudio 兼容的 WITSML 对象和字段语义，在现有 Java 鉴权、工程层级、文件版次和轨迹版本事务中完成落库。上游项目是 .NET WITSML Store，不能直接作为 Java/Spring 运行时嵌入；本项目保留其来源、许可和通知信息，并使用独立的 Python 适配边界完成同等数据协议接入。

上游来源：<https://github.com/pds-technology/witsml>

上游许可和通知：见该项目的 `LICENSE.txt` 与 `NOTICE.txt`。本系统没有复制其实现代码；如以后分发 PDS.WITSMLstudio 源码或二进制，应同时遵守其许可中的署名、通知和再分发条款。
