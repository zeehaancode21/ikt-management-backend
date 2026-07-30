package com.example.backend.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.backend.entity.Group;
import com.example.backend.entity.Message;
import com.example.backend.entity.Notification;
import com.example.backend.entity.User;
import com.example.backend.repository.AttachmentRepository;
import com.example.backend.repository.DocumentRepository;
import com.example.backend.repository.EmployeeBankDetailRepository;
import com.example.backend.repository.EmployeeProfileRepository;
import com.example.backend.repository.FcmTokenRepository;
import com.example.backend.repository.FolderRepository;
import com.example.backend.repository.GroupMessageRepository;
import com.example.backend.repository.GroupRepository;
import com.example.backend.repository.LeaveRequestRepository;
import com.example.backend.repository.MessageRepository;
import com.example.backend.repository.NotificationRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.repository.WorkReportRepository;

@Service
public class EmployeeService {

    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private FolderRepository folderRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private WorkReportRepository workReportRepository;
    @Autowired private FcmTokenRepository fcmTokenRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupMessageRepository groupMessageRepository;
    @Autowired private AttachmentRepository attachmentRepository;
    
    // Add these new repositories
    @Autowired private EmployeeProfileRepository employeeProfileRepository;
    @Autowired private EmployeeBankDetailRepository employeeBankDetailRepository;

    @Transactional
    public void deleteAllLeaveRequestsForUser(Long employeeId) {
        User user = userRepository.findById(employeeId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + employeeId));
        String username = user.getUsername();

        // 1. Work reports & leave requests
        workReportRepository.deleteByEmployeeName(username);
        leaveRequestRepository.deleteByEmployeeName(username);

        // 2. Documents & folders
        documentRepository.deleteByUploadedBy(username);
        folderRepository.deleteByCreatedBy(username);

        // 3. Messages — clear ManyToMany join table first, then delete.
        //    JPQL DELETE bypasses cascade so we must detach attachments first
        //    or the FK rows in message_attachments will cause a constraint violation.
        List<Message> userMessages = messageRepository
                .findAll()
                .stream()
                .filter(m -> username.equals(m.getSenderUsername()) || username.equals(m.getReceiverUsername()))
                .toList();
        for (Message m : userMessages) {
            m.getAttachments().clear();
        }
        messageRepository.saveAll(userMessages);
        messageRepository.deleteAllMessagesByUsername(username);

        // 4. Notifications — clear join table first
        List<Notification> userNotifications = notificationRepository.findAllForUser(username);
        for (Notification n : userNotifications) {
            n.getAttachments().clear();
        }
        notificationRepository.saveAll(userNotifications);
        notificationRepository.deleteAllNotificationsForUser(username);

        // 5. FCM tokens
        fcmTokenRepository.deleteByUsername(username);

        // 6. Groups — delete groups they created, remove them from groups they joined
        List<Group> allUserGroups = groupRepository.findGroupsForUser(username);
        for (Group group : allUserGroups) {
            if (username.equals(group.getCreatedBy())) {
                groupMessageRepository.deleteAllByGroupId(group.getId());
                groupRepository.delete(group);
            } else {
                List<String> members = group.getMemberList();
                members.remove(username);
                group.setMemberList(members);
                groupRepository.save(group);
            }
        }

        // 7. Attachments uploaded by this user
        attachmentRepository.deleteAllByUploadedBy(username);

        // 8. Delete employee profile and bank details
        // Find and delete profile by username
        employeeProfileRepository.findByUsername(username)
                .ifPresent(profile -> employeeProfileRepository.delete(profile));
        
        // Find and delete bank details by username
        employeeBankDetailRepository.findByEmployeeUsername(username)
                .ifPresent(bankDetail -> employeeBankDetailRepository.delete(bankDetail));

        // 9. Finally delete the user account
        userRepository.deleteById(employeeId);
    }
}