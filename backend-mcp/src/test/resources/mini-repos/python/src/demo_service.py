class Base:
    def base_method(self):
        return "base"


class DemoService(Base):
    def process(self, name: str, count: int) -> str:
        helper(count)
        return f"{name}-{count}"


def helper(count: int):
    print("count", count)
