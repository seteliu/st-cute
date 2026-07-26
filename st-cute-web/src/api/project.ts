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
