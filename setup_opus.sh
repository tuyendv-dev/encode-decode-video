#!/bin/bash

# Script to setup Opus library for Android demo app
# Run this from the root of your Android project

set -e

echo "=========================================="
echo "Opus Android Demo - Setup Script"
echo "=========================================="

# Configuration
ANDROID_HOME=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
NDK_VERSION="27.0.12077973"
NDK_PATH="$ANDROID_HOME/ndk/$NDK_VERSION"
TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64"
MIN_API=24

# Check Android SDK
if [ ! -d "$ANDROID_HOME" ]; then
    echo "❌ Error: ANDROID_HOME not found at $ANDROID_HOME"
    echo "Please set ANDROID_HOME environment variable"
    exit 1
fi

# Check NDK
if [ ! -d "$NDK_PATH" ]; then
    echo "❌ Error: NDK not found at $NDK_PATH"
    echo "Please install NDK $NDK_VERSION from Android Studio SDK Manager"
    exit 1
fi

echo "✓ Android SDK: $ANDROID_HOME"
echo "✓ NDK: $NDK_PATH"

# Create directories
mkdir -p build-opus
cd build-opus

# Download Opus source
if [ ! -d "opus" ]; then
    echo ""
    echo "📥 Downloading Opus source code..."
    git clone --depth 1 https://github.com/xiph/opus.git
    cd opus
    ./autogen.sh
    cd ..
    echo "✓ Opus source downloaded"
else
    echo "✓ Opus source already exists"
fi

cd opus

# Output directory
OUTPUT_DIR="../android-libs"
mkdir -p "$OUTPUT_DIR"/{armeabi-v7a,arm64-v8a,x86,x86_64}

# Function to build for specific architecture
build_arch() {
    local ARCH=$1
    local HOST=$2
    local COMPILER_PREFIX=$3
    local OUTPUT_FOLDER=$4
    
    echo ""
    echo "🔨 Building for $ARCH..."
    
    make clean 2>/dev/null || true
    
    export CC="$TOOLCHAIN/bin/${COMPILER_PREFIX}${MIN_API}-clang"
    export CXX="$TOOLCHAIN/bin/${COMPILER_PREFIX}${MIN_API}-clang++"
    export AR="$TOOLCHAIN/bin/llvm-ar"
    export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    export LDFLAGS="-lm"
    
    # Configure flags để đảm bảo symbols được export
    CFLAGS="-O2 -fPIC" \
    CXXFLAGS="-O2 -fPIC" \
    ./configure \
        --host=$HOST \
        --prefix=$(pwd)/$OUTPUT_DIR/$OUTPUT_FOLDER \
        --disable-shared \
        --enable-static \
        --enable-float-approx \
        --disable-doc \
        --disable-extra-programs
    
    make -j$(sysctl -n hw.ncpu)
    make install
    
    # Verify library has symbols
    echo "  Verifying symbols in libopus.a..."
    if nm "$OUTPUT_DIR/$OUTPUT_FOLDER/lib/libopus.a" | grep -q "opus_decoder_create"; then
        echo "  ✓ Symbols verified"
    else
        echo "  ✗ WARNING: opus_decoder_create not found in library!"
    fi
    
    echo "✅ Completed $ARCH"
}

# Build for all architectures
build_arch "armeabi-v7a" "arm-linux-androideabi" "armv7a-linux-androideabi" "armeabi-v7a"
build_arch "arm64-v8a" "aarch64-linux-android" "aarch64-linux-android" "arm64-v8a"
build_arch "x86" "i686-linux-android" "i686-linux-android" "x86"
build_arch "x86_64" "x86_64-linux-android" "x86_64-linux-android" "x86_64"

echo ""
echo "=========================================="
echo "✅ Build completed successfully!"
echo "=========================================="

# Setup project structure
cd ../..
PROJECT_CPP_DIR="app/src/main/cpp"

echo ""
echo "📁 Setting up project structure..."

# Create cpp directories
mkdir -p "$PROJECT_CPP_DIR/opus/include/opus"
mkdir -p "$PROJECT_CPP_DIR/opus/lib"/{armeabi-v7a,arm64-v8a,x86,x86_64}

# Copy headers
echo "📋 Copying Opus headers..."
cp build-opus/opus/include/*.h "$PROJECT_CPP_DIR/opus/include/opus/"

# Copy libraries
echo "📋 Copying Opus libraries..."
for arch in armeabi-v7a arm64-v8a x86 x86_64; do
    cp "build-opus/android-libs/$arch/lib/libopus.a" "$PROJECT_CPP_DIR/opus/lib/$arch/"
done

echo ""
echo "✅ Setup completed!"
echo ""
echo "Project structure:"
echo "$PROJECT_CPP_DIR/"
echo "├── opus/"
echo "│   ├── include/opus/"
echo "│   │   └── *.h"
echo "│   └── lib/"
echo "│       ├── armeabi-v7a/libopus.a"
echo "│       ├── arm64-v8a/libopus.a"
echo "│       ├── x86/libopus.a"
echo "│       └── x86_64/libopus.a"
echo "├── CMakeLists.txt"
echo "└── opus_jni.cpp"
echo ""
echo "🚀 Next steps:"
echo "1. Make sure CMakeLists.txt and opus_jni.cpp are in $PROJECT_CPP_DIR"
echo "2. Run: ./gradlew clean"
echo "3. Run: ./gradlew assembleDebug"
echo "4. Install and run the app!"
echo ""
