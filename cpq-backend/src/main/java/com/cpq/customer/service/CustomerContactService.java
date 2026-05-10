package com.cpq.customer.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.customer.dto.ContactDTO;
import com.cpq.customer.entity.Customer;
import com.cpq.customer.entity.CustomerContact;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class CustomerContactService {

    private static final Logger LOG = Logger.getLogger(CustomerContactService.class);
    private static final String PHONE_PATTERN = "^\\d{11}$";

    public List<ContactDTO> listByCustomer(UUID customerId) {
        return CustomerContact.<CustomerContact>list("customerId = ?1 ORDER BY isPrimary DESC, createdAt ASC", customerId)
                .stream()
                .map(ContactDTO::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public ContactDTO create(UUID customerId, ContactDTO dto) {
        Customer customer = Customer.findById(customerId);
        if (customer == null) {
            throw new BusinessException(404, "Customer not found: " + customerId);
        }
        validatePhone(dto.phone);

        CustomerContact contact = new CustomerContact();
        contact.customerId = customerId;
        contact.name = dto.name;
        contact.role = dto.role;
        contact.phone = dto.phone;
        contact.email = dto.email;
        contact.wechat = dto.wechat;
        contact.isPrimary = dto.isPrimary != null ? dto.isPrimary : false;

        if (Boolean.TRUE.equals(contact.isPrimary)) {
            unsetOtherPrimaries(customerId, null);
        }

        contact.persist();
        LOG.infof("Created contact customerId=%s name=%s phone=%s", customerId, contact.name, contact.phone);
        return ContactDTO.from(contact);
    }

    @Transactional
    public ContactDTO update(UUID contactId, ContactDTO dto) {
        CustomerContact contact = CustomerContact.findById(contactId);
        if (contact == null) {
            throw new BusinessException(404, "Contact not found: " + contactId);
        }
        if (dto.phone != null) {
            validatePhone(dto.phone);
            contact.phone = dto.phone;
        }
        if (dto.name != null) contact.name = dto.name;
        if (dto.role != null) contact.role = dto.role;
        if (dto.email != null) contact.email = dto.email;
        if (dto.wechat != null) contact.wechat = dto.wechat;
        if (dto.isPrimary != null) {
            if (Boolean.TRUE.equals(dto.isPrimary)) {
                unsetOtherPrimaries(contact.customerId, contactId);
            }
            contact.isPrimary = dto.isPrimary;
        }
        LOG.infof("Updated contact id=%s", contactId);
        return ContactDTO.from(contact);
    }

    @Transactional
    public void delete(UUID contactId) {
        CustomerContact contact = CustomerContact.findById(contactId);
        if (contact == null) {
            throw new BusinessException(404, "Contact not found: " + contactId);
        }
        if (Boolean.TRUE.equals(contact.isPrimary)) {
            long primaryCount = CustomerContact.count("customerId = ?1 AND isPrimary = true", contact.customerId);
            if (primaryCount <= 1) {
                throw new BusinessException("Cannot delete the last primary contact");
            }
        }
        contact.delete();
        LOG.infof("Deleted contact id=%s", contactId);
    }

    @Transactional
    public ContactDTO setPrimary(UUID customerId, UUID contactId) {
        CustomerContact contact = CustomerContact.findById(contactId);
        if (contact == null || !customerId.equals(contact.customerId)) {
            throw new BusinessException(404, "Contact not found: " + contactId);
        }
        unsetOtherPrimaries(customerId, contactId);
        contact.isPrimary = true;
        LOG.infof("Set primary contact customerId=%s contactId=%s", customerId, contactId);
        return ContactDTO.from(contact);
    }

    private void unsetOtherPrimaries(UUID customerId, UUID excludeContactId) {
        List<CustomerContact> primaries = CustomerContact.<CustomerContact>list(
                "customerId = ?1 AND isPrimary = true", customerId);
        for (CustomerContact c : primaries) {
            if (excludeContactId == null || !excludeContactId.equals(c.id)) {
                c.isPrimary = false;
            }
        }
    }

    private void validatePhone(String phone) {
        if (phone == null || !phone.matches(PHONE_PATTERN)) {
            throw new BusinessException("Phone must be 11 digits: " + phone);
        }
    }
}
