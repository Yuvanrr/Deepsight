DeepSight
> **Real-time on-device synthetic content detection for Android**
DeepSight is an Android application that detects AI-generated and deepfake content directly on the device — no cloud calls, no latency overhead. It runs a dual-model TFLite pipeline over live screen captures using the MediaProjection API, classifying both images and video streams in real time.
Accepted to RC 2026 (IEEE).
---
How It Works
DeepSight intercepts screen content via Android's MediaProjection API and routes frames through two independent models depending on content type:
```
Screen Capture (MediaProjection)
        │
        ▼
  ML Kit Face Detection
        │
   ┌────┴────┐
   │         │
Image      Video
   │         │
MobileNetV3  CNN+LSTM
Small        
   │         │
   └────┬────┘
        │
  Real / Fake
  Classification
```
MobileNetV3Small — image classification, 88.94% accuracy (Phase 2), ~43ms inference latency
CNN+LSTM — temporal video classification across frame sequences
ModelSelector — routing logic that picks the appropriate model per frame type
Floating overlay — always-on result display via System Alert Window
---
Features
On-device inference — zero data leaves the device
Real-time classification during screen share / recording
Dual-model pipeline (image + video)
Floating HUD overlay visible across all apps
ML Kit face detection as a preprocessing gate
---
Tech Stack
Layer	Technology
Language	Kotlin
UI	Jetpack Compose
Screen capture	MediaProjection API
Face detection	ML Kit
Inference	TensorFlow Lite
Image model	MobileNetV3Small (`.tflite`)
Video model	CNN+LSTM (`.tflite`)
Overlay	System Alert Window
---
Model Details
Image Model — MobileNetV3Small
Metric	Value
Accuracy (Phase 2)	88.94%
Inference latency	~43ms
Input normalization	`NormalizeOp(127.5f, 127.5f)`
Format	TFLite (`.tflite`)
Training used a balanced real-vs-fake face image dataset with a subject-level 80/20 train/test split to prevent identity leakage. Trained on Kaggle (Tesla T4) and locally on Fedora with TF 2.21, CUDA 12.6, cuDNN 9.3.
Video Model — CNN+LSTM
Temporal model that processes sequences of frames to detect synthetic patterns across time — handles videos that might fool single-frame classifiers.
---
Project Structure
```
DeepSight/
├── app/
│   └── src/main/
│       ├── java/com/deepsight/
│       │   ├── detection/
│       │   │   ├── ImageClassifier.kt       # MobileNetV3Small wrapper
│       │   │   ├── VideoClassifier.kt       # CNN+LSTM wrapper
│       │   │   └── ModelSelector.kt         # Routing logic
│       │   ├── capture/
│       │   │   └── ScreenCaptureService.kt  # MediaProjection handler
│       │   ├── overlay/
│       │   │   └── FloatingOverlay.kt       # System Alert Window HUD
│       │   └── ui/
│       │       └── MainActivity.kt
│       ├── assets/
│       │   ├── best_phase2.tflite           # Image model
│       │   └── video_model.tflite           # Video model
│       └── res/
├── README.md
└── build.gradle
```
---
Getting Started
Prerequisites
Android Studio Hedgehog or later
Android device / emulator running API 26+
`FOREGROUND_SERVICE`, `MEDIA_PROJECTION`, `SYSTEM_ALERT_WINDOW` permissions
Build
```bash
git clone https://github.com/Yuvanrr/DeepSight.git
cd DeepSight
./gradlew assembleDebug
```
Install on device:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```
Permissions
On first launch, DeepSight will request:
Display over other apps — required for the floating overlay
Screen recording — required for MediaProjection capture
---
Research
This project was published as an IEEE conference paper at RC 2026:
> *DeepSight: Real-Time On-Device Synthetic Content Detection on Android*  
> Yuvan Raaju R — PSG College of Technology, Coimbatore
The paper covers the dual-model pipeline architecture, dataset construction methodology, Phase 2 accuracy improvements, and latency benchmarks against cloud-based alternatives.
---
Limitations
Image model accuracy is 88.94% — not production-grade for high-stakes use
Video model performance is sensitive to frame rate and resolution
Requires Android 8.0+ (API 26) for full MediaProjection support
System Alert Window permission may require manual grant on some OEMs
---
Author
Yuvan Raaju R  
MCA — PSG College of Technology, Coimbatore  
github.com/Yuvanrr
---
License
MIT License. See LICENSE for details.
