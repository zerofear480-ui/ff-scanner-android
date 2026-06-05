last_ocr_debug = {"ocr_text": "", "players": []}

@app.post("/api/ocr-debug")
async def ocr_debug(payload: dict):
    global last_ocr_debug
    last_ocr_debug = payload
    return {"ok": True, "saved": True}

@app.get("/api/ocr-debug")
async def get_ocr_debug():
    return last_ocr_debug
