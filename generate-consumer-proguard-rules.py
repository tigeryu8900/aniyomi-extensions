from pathlib import Path
import re

DIR = Path(__file__).resolve().parent

def main() -> None:
    with DIR.joinpath("stub/api/stub.api").open(mode="r", encoding="utf-8") as f:
        api = f.read()
    with DIR.joinpath("common/stub-rules.pro").open(mode="w", encoding="utf-8") as f:
        for x in re.findall(r"^public [\w ]+ class ([\w/]+)", api, flags=re.MULTILINE):
            f.write(f"-dontwarn {x.replace("/", ".")}\n")


if __name__ == "__main__":
    main()
