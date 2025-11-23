package main

import "fmt"

type Runner interface {
	Run()
}

type Base struct{}

func (b Base) Log(value int) {
	fmt.Println("base", value)
}

type DemoService struct {
	Base
}

func (d DemoService) Run() {
	d.Process("demo", 3)
}

func (d DemoService) Process(name string, count int) string {
	d.Log(count)
	helper(count)
	return fmt.Sprintf("%s-%d", name, count)
}

func helper(count int) {
	fmt.Println("count", count)
}

func main() {
	var r Runner = DemoService{}
	r.Run()
}
