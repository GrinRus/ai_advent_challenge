package main

import "fmt"

type Repository interface {
	Save(name string)
}

type MemoryRepository struct {
	last string
}

func (m *MemoryRepository) Save(name string) {
	m.last = name
	fmt.Println("saved", name)
}

func (m *MemoryRepository) Last() string {
	return m.last
}
