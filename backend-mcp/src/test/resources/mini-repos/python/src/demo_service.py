from .helpers import helper, Repository


class Base:
    """Base class with a hook."""

    def base_method(self):
        return "base"

    def log(self, value: int):
        print("base", value)


class DemoService(Base):
    """Demo service docstring."""

    def __init__(self) -> None:
        self.repository = Repository()

    def process(self, name: str, count: int) -> str:
        self.log(count)
        helper(count)
        self.repository.find(name)
        return f"{name}-{count}"

    def process_once(self, name: str) -> str:
        return self.process(name, 1)
