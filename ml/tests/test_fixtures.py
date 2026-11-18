import json

import pytest

from make_fixtures import FILES, FIXTURES


@pytest.mark.parametrize("name", FILES)
def test_fixture_shared_with_the_app_is_current(name):
    """If this fails after an intended change, run make_fixtures.py and fix the Kotlin side too."""
    stored = json.loads((FIXTURES / name).read_text())
    assert stored == json.loads(json.dumps(FILES[name]()))
