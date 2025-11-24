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
	repo Repository
}

func (d DemoService) Run() {
	d.Process("demo", 3)
}

func (d DemoService) Process(name string, count int) string {
	d.Log(count)
	helper(count)
	if d.repo != nil {
		d.repo.Save(name)
	}
	return fmt.Sprintf("%s-%d", name, count)
}

func helper(count int) {
	fmt.Println("count", count)
}

func main() {
	service := DemoService{repo: &MemoryRepository{}}
	var r Runner = service
	r.Run()
}
