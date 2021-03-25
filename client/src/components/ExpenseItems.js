import React from 'react';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import { faTrash } from '@fortawesome/free-solid-svg-icons';
import axios from 'axios';

function ExpenseItems({expense, setExpenseData, currentDate}) {

    const handleDelete = async (event) => {
        const id = event.target.id;
        await axios.delete(`/api/bills/${id}`)
        axios.get(`/api/users/bill/${currentDate.month}/${currentDate.year}`).then((response) => setExpenseData(response.data.bills)).catch(err => {
          console.log(err)
          setExpenseData([{name: 'No Bills Yet!'}])
        })
    }

    const handleCheck = async (event) => {
      const id = event.target.id;
      let isPayed;
      if (event.target.checked) {
        isPayed = true;
      } else {
        isPayed = false;
      }

      await axios.put(`/api/bills/${id}`, {
        isPayed
      })

      axios.get(`/api/users/bill/${currentDate.month}/${currentDate.year}`).then((response) => setExpenseData(response.data.bills)).catch(err => {
        console.log(err);
        setExpenseData([{name: 'No Expense Yet!'}])
      })
    }

  return (
    <>
       <tr>
           <td>{expense.name}</td>
           <td>{expense.amount}</td>
           <td><input type="checkbox" id={expense.id} onClick={handleCheck} checked={expense.is_payed} /></td>
           <td onClick={handleDelete} className='trashbtn' id={expense.id}><FontAwesomeIcon className="trash" icon={faTrash} style={{pointerEvents: 'none'}} /></td>
       </tr>
    </>
  );
}


export default ExpenseItems;
