import request from '@/utils/request'
import { Project } from '@/types'

export const getProjects = async (): Promise<Project[]> => {
  return request.get('/api/project/list')
}

export const saveProject = async (project: Partial<Project>): Promise<Project> => {
  return request.post('/api/project/save', project)
}

export const deleteProjectById = async (id: number): Promise<any> => {
  return request.delete(`/api/project/delete?id=${id}`)
}

export const updateProjectExpanded = async (id: number, expanded: boolean): Promise<boolean> => {
  return request.post(`/api/project/update-expanded?id=${id}&expanded=${expanded}`)
}

export const setActiveProject = async (id: number): Promise<boolean> => {
  return request.post(`/api/project/set-active?id=${id}`)
}
