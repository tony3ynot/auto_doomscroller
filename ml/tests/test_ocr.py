from autodoom.ocr import clean_text


def word(text, x0, y0, x1=None, y1=None, conf=0.9):
    return {"text": text, "conf": conf, "box": [x0, y0, x1 or x0 + 0.3, y1 or y0 + 0.03]}


def test_keeps_hook_text_in_reading_order_and_drops_ui():
    words = [
        word("말아옴", 0.40, 0.10),
        word("카리나가 비하인드", 0.05, 0.10),
        word("1600만 찍은", 0.10, 0.05),
        word("팔로우", 0.30, 0.70),            # UI label
        word("5.5만", 0.88, 0.40),             # action column on the right
        word("1.6만", 0.40, 0.50),             # a bare count
        word("scope_by_sports", 0.10, 0.85),   # author row at the bottom
        word("scope_by_sports", 0.10, 0.30),   # the creator's name anywhere
        word("흐릿한", 0.10, 0.20, conf=0.1),  # low confidence
        word("@", 0.10, 0.25),                 # no letters
        word("회원님올 위한 추천", 0.40, 0.03),  # the app's tab bar, misspelled by OCR
    ]
    assert clean_text(words, known=("scope_by_sports",)) == "1600만 찍은 카리나가 비하인드 말아옴"


def test_nothing_left_is_none():
    assert clean_text([word("구독", 0.2, 0.2)]) is None
    assert clean_text([]) is None
