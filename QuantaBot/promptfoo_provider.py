"""Promptfoo resume fallback for the M4 provider.

Promptfoo 0.123.1 may drop the config base path when resuming a stored eval and
then resolves ``file://promptfoo_provider.py`` from the repository root.  The
canonical implementation remains in ``eval/promptfoo_provider.py``; this shim
keeps fresh and resumed runs on the same entrypoint.
"""

import importlib.util
from pathlib import Path

_CANONICAL_PROVIDER = Path(__file__).resolve().parent / "eval/promptfoo_provider.py"
_SPEC = importlib.util.spec_from_file_location("_m4_promptfoo_provider", _CANONICAL_PROVIDER)
if _SPEC is None or _SPEC.loader is None:  # pragma: no cover - importlib invariant
    raise ImportError(f"cannot load M4 Promptfoo provider: {_CANONICAL_PROVIDER}")
_MODULE = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_MODULE)
call_api = _MODULE.call_api

__all__ = ["call_api"]
