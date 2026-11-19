cmake_minimum_required(VERSION 3.22)
project(sosduo CXX)
add_library(sosduo SHARED reader.cpp)
target_link_libraries(sosduo log)
