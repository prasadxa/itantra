import sys
from pathlib import Path

# Makes `import cap`, `import db`, `import frames`, `import gateway` work under pytest
# regardless of invocation directory (these are plain top-level modules, not a package).
sys.path.insert(0, str(Path(__file__).resolve().parent))
