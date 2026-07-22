# 小米14U AI构图相机

专为小米14 Ultra深度适配的AI智能构图相机应用。

## 功能特性

### 高级AI构图分析
- **深度学习模型**: TFLite模型实时分析取景画面
- **构图评分**: 8个维度实时评分（三分法/对称/平衡/线条/深度/色彩/主体/总分）
- **构图等级**: S/A/B/C/D 五级评定
- **裁剪建议**: AI推荐最佳裁剪区域，预期评分提升
- **角度建议**: 智能推荐拍摄俯仰角和横滚角
- **模式检测**: 自动识别当前构图模式（三分法/黄金比例/对称/中心等）

### 构图辅助线
- 三分法网格 + 交叉点标记
- 黄金比例分割线
- 黄金螺旋
- 对角线引导
- 对称轴线
- 中心构图准星
- 引导线模式

### 场景识别（ML Kit）
- 人像/风景/美食/夜景/微距/运动/文档/室内
- 自动推荐相机参数（光圈/ISO/曝光补偿/饱和度）

### AI自动拍摄
- 微笑检测 + 表情评分
- 运动稳定性追踪
- 最佳时机智能抓拍

### 小米14U硬件适配
- Camera2 API深度控制
- 四摄切换（12mm/23mm/75mm/120mm）
- 无级可变光圈（f/1.63-f/4.0）

## 技术栈
- Kotlin + Jetpack Compose
- Camera2 API
- ML Kit（场景识别 + 人脸检测）
- TensorFlow Lite（构图评分模型）

## 构建运行
1. Android Studio 打开项目目录
2. 同步 Gradle
3. 连接小米14U
4. Run `app`

## 项目结构
```
app/src/main/java/com/xiaomi/ai_camera/
├── MainActivity.kt
├── ai/
│   ├── CompositionAnalyzer.kt  # 核心：深度学习构图分析
│   ├── SceneAnalyzer.kt        # 场景识别
│   ├── FaceDetector.kt         # 人脸检测
│   └── MotionTracker.kt        # 运动追踪
├── camera/
│   └── CameraManager.kt        # Camera2管理器
├── ui/camera/
│   ├── CameraScreen.kt         # 主界面
│   ├── CameraPreview.kt        # 预览
│   ├── CameraControls.kt       # 控制栏
│   └── CompositionOverlay.kt   # 构图叠加层
└── viewmodel/
    └── CameraViewModel.kt
```
