#!/bin/bash

echo "╔══════════════════════════════════════════════════════╗"
echo "║  TEST UPLOAD MÉDIAS - Phase 2 Module 3              ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""

# Register test user
echo "📝 Registering test user..."
TIMESTAMP=$(date +%s)
USER_REG=$(curl -s -X POST http://localhost:8090/api/auth/register \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"mediatest${TIMESTAMP}\",\"email\":\"mediatest${TIMESTAMP}@test.com\",\"password\":\"Test1234!\",\"firstName\":\"Media\",\"lastName\":\"Tester\",\"displayName\":\"Media Tester\"}")

TOKEN=$(echo "$USER_REG" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
  echo "[FAIL] Registration failed: $USER_REG"
  exit 1
fi

echo "[OK] User registered"
echo "Token: ${TOKEN:0:30}..."
echo ""

# Create test image
echo "🎨 Creating test image..."
convert -size 800x600 xc:blue -pointsize 50 -fill white \
  -gravity center -annotate +0+0 "Test Image Pair" \
  /tmp/test-image.jpg 2>/dev/null

if [ ! -f /tmp/test-image.jpg ]; then
  echo "[WARN] ImageMagick not available, creating dummy file"
  # Create a valid JPEG header for testing
  echo -e "\xFF\xD8\xFF\xE0\x00\x10JFIF" > /tmp/test-image.jpg
  dd if=/dev/urandom bs=1024 count=50 >> /tmp/test-image.jpg 2>/dev/null
fi

echo "[OK] Test image created"
echo ""

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Test 1: Upload Image"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
UPLOAD_RESULT=$(curl -s -X POST http://localhost:8090/api/media/upload/image \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/test-image.jpg" \
  -F "type=PROGRAM_IMAGE")

echo "$UPLOAD_RESULT" | python -m json.tool 2>/dev/null || echo "$UPLOAD_RESULT"
echo ""

# Extract URL from response
FILE_URL=$(echo "$UPLOAD_RESULT" | grep -o '"url":"[^"]*"' | cut -d'"' -f4)
FILENAME=$(echo "$UPLOAD_RESULT" | grep -o '"filename":"[^"]*"' | cut -d'"' -f4)

if [ ! -z "$FILE_URL" ]; then
  echo "✅ Upload successful"
  echo "   URL: $FILE_URL"
  echo "   Filename: $FILENAME"
else
  echo "❌ Upload failed"
fi
echo ""

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Test 2: Upload Avatar"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
AVATAR_RESULT=$(curl -s -X POST http://localhost:8090/api/media/upload/avatar \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/test-image.jpg")

echo "$AVATAR_RESULT" | python -m json.tool 2>/dev/null | head -10
echo ""

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Test 3: Serve File"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
if [ ! -z "$FILE_URL" ]; then
  echo "Attempting to download: $FILE_URL"
  HTTP_CODE=$(curl -s -w "%{http_code}" -o /tmp/downloaded.jpg \
    -H "Authorization: Bearer $TOKEN" \
    "http://localhost:8090${FILE_URL}")

  echo "HTTP Code: $HTTP_CODE"

  if [ "$HTTP_CODE" == "200" ]; then
    FILE_SIZE=$(stat -f%z /tmp/downloaded.jpg 2>/dev/null || stat -c%s /tmp/downloaded.jpg 2>/dev/null)
    echo "✅ File downloaded successfully"
    echo "   Size: $FILE_SIZE bytes"
  else
    echo "❌ Download failed"
  fi
else
  echo "⏭️  Skipped (no file URL)"
fi
echo ""

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Test 4: Upload Invalid File (Size Limit)"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Creating 11MB file..."
dd if=/dev/zero of=/tmp/large-file.jpg bs=1M count=11 2>/dev/null

LARGE_RESULT=$(curl -s -X POST http://localhost:8090/api/media/upload/image \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/large-file.jpg" \
  -F "type=PROGRAM_IMAGE")

echo "$LARGE_RESULT" | python -m json.tool 2>/dev/null | head -10
echo ""

if echo "$LARGE_RESULT" | grep -q "size"; then
  echo "✅ Size limit validation works"
else
  echo "⚠️  Size limit might not be enforced"
fi
echo ""

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Test 5: Upload Invalid Type"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "test file content" > /tmp/test.txt

INVALID_RESULT=$(curl -s -X POST http://localhost:8090/api/media/upload/image \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/test.txt" \
  -F "type=PROGRAM_IMAGE")

echo "$INVALID_RESULT" | python -m json.tool 2>/dev/null | head -10
echo ""

if echo "$INVALID_RESULT" | grep -qi "type\|mime"; then
  echo "✅ MIME type validation works"
else
  echo "⚠️  MIME type validation might not be enforced"
fi
echo ""

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Test 6: Check Storage Directory"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
if [ -d "uploads" ]; then
  echo "Storage directory structure:"
  find uploads -type d 2>/dev/null | head -10
  echo ""
  echo "Files count:"
  find uploads -type f 2>/dev/null | wc -l
  echo "✅ Storage initialized"
else
  echo "❌ Storage directory not found"
fi
echo ""

# Cleanup
echo "🧹 Cleaning up test files..."
rm -f /tmp/test-image.jpg /tmp/downloaded.jpg /tmp/large-file.jpg /tmp/test.txt

echo "╔══════════════════════════════════════════════════════╗"
echo "║                  Tests Completed!                    ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""
echo "✅ Fonctionnalités testées:"
echo "  - Upload image"
echo "  - Upload avatar"
echo "  - Serve file (download)"
echo "  - Size limit validation"
echo "  - MIME type validation"
echo "  - Storage directory"
echo ""
echo "Module 3 Upload Médias: Tests terminés ✅"
